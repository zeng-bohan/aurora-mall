package com.zengbohan.aurora.inventory.stock;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.inventory.entity.ProductStock;
import com.zengbohan.aurora.inventory.entity.StockReservation;
import com.zengbohan.aurora.inventory.mapper.ProductStockMapper;
import com.zengbohan.aurora.inventory.mapper.StockReservationMapper;
import com.zengbohan.aurora.inventory.mapper.StockRestoreMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private static final long OK = 1L;
    private static final long MISSING_KEY = -1L;
    private static final long INSUFFICIENT = -2L;
    private static final long ALREADY_RESERVED = -3L;

    private final StringRedisTemplate redis;
    private final StockLuaScripts scripts;
    private final ProductStockMapper stockMapper;
    private final StockReservationMapper reservationMapper;
    private final StockRestoreMapper restoreMapper;
    private final TransactionTemplate transactionTemplate;

    public StockService(StringRedisTemplate redis, StockLuaScripts scripts,
                        ProductStockMapper stockMapper, StockReservationMapper reservationMapper,
                        StockRestoreMapper restoreMapper, TransactionTemplate transactionTemplate) {
        this.redis = redis;
        this.scripts = scripts;
        this.stockMapper = stockMapper;
        this.reservationMapper = reservationMapper;
        this.restoreMapper = restoreMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public void reserve(long tradeId, List<StockDtos.StockItem> items) {
        List<StockDtos.StockItem> normalized = normalize(items);
        if (!reservationMapper.findByTrade(tradeId).isEmpty()) {
            log.info("reserve skipped: trade {} already has reservations", tradeId);
            return;
        }
        ensureStockKeys(normalized);
        long result = executeReserve(tradeId, normalized);
        if (result == MISSING_KEY) {
            ensureStockKeys(normalized);
            result = executeReserve(tradeId, normalized);
        }
        if (result == ALREADY_RESERVED) {
            if (!reservationMapper.findByTrade(tradeId).isEmpty()) {
                return;
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "库存预占状态不一致，请稍后重试");
        }
        if (result == INSUFFICIENT) {
            throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT);
        }
        if (result != OK) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "库存服务异常");
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                for (StockDtos.StockItem item : normalized) {
                    if (reservationMapper.insertIfAbsent(tradeId, item.skuId(), item.quantity()) == 1) {
                        if (stockMapper.incrementReserved(item.skuId(), item.quantity()) == 0) {
                            throw new BusinessException(ErrorCode.NOT_FOUND, "SKU " + item.skuId() + " 未开通库存");
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            releaseRedis(tradeId, normalized);
            throw e;
        }
    }

    public void release(long tradeId, List<StockDtos.StockItem> ignored) {
        List<StockReservation> reservations = reservationMapper.findByTrade(tradeId);
        List<StockReservation> reserved = reservations.stream()
                .filter(r -> "RESERVED".equals(r.getStatus())).toList();
        if (reserved.isEmpty()) {
            return;
        }
        List<StockDtos.StockItem> items = reserved.stream()
                .map(r -> new StockDtos.StockItem(r.getSkuId(), r.getQuantity())).toList();
        try {
            ensureStockKeys(items);
            releaseRedis(tradeId, items);
        } catch (RuntimeException e) {
            log.error("redis release failed for trade {}; reconcile will repair keys", tradeId, e);
        }
        transactionTemplate.executeWithoutResult(status -> {
            for (StockReservation row : reserved) {
                if (reservationMapper.markReleased(tradeId, row.getSkuId()) == 1) {
                    if (stockMapper.decrementReserved(row.getSkuId(), row.getQuantity()) == 0) {
                        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                                "库存台账释放失败，请稍后重试");
                    }
                }
            }
        });
    }

    public void confirm(long tradeId, List<StockDtos.StockItem> ignored) {
        List<StockReservation> reservations = reservationMapper.findByTrade(tradeId);
        for (StockReservation row : reservations) {
            if (!"RESERVED".equals(row.getStatus())) {
                continue;
            }
            transactionTemplate.executeWithoutResult(status -> {
                if (stockMapper.confirmReservation(row.getSkuId(), row.getQuantity()) == 0) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                            "库存确认失败：预占数量不足");
                }
                reservationMapper.markConfirmed(tradeId, row.getSkuId());
            });
        }
    }

    public void restore(long refundId, long tradeId, List<StockDtos.StockItem> items) {
        List<StockDtos.StockItem> normalized = normalize(items);
        ensureStockKeys(normalized);
        List<StockDtos.StockItem> restored = new ArrayList<>();
        transactionTemplate.executeWithoutResult(status -> {
            for (StockDtos.StockItem item : normalized) {
                if (restoreMapper.insertIfAbsent(refundId, item.skuId(), item.quantity()) == 0) {
                    continue;
                }
                StockReservation reservation = reservationMapper.find(tradeId, item.skuId());
                if (reservation != null && "RESERVED".equals(reservation.getStatus())) {
                    if (stockMapper.confirmReservation(item.skuId(), item.quantity()) == 0) {
                        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                                "退款回补失败：预占数量不足");
                    }
                    reservationMapper.markConfirmed(tradeId, item.skuId());
                } else if (reservation != null && "RELEASED".equals(reservation.getStatus())) {
                    log.warn("refund {} targets released reservation ({}, {})",
                            refundId, tradeId, item.skuId());
                    continue;
                }
                if (stockMapper.restoreAvailable(item.skuId(), item.quantity()) == 0) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "SKU " + item.skuId() + " 未开通库存");
                }
                restored.add(item);
            }
        });
        if (restored.isEmpty()) {
            return;
        }
        List<String> keys = restored.stream()
                .map(item -> StockLuaScripts.key(item.skuId())).toList();
        Object[] args = restored.stream().map(item -> String.valueOf(item.quantity())).toArray();
        Long result = redis.execute(scripts.restore, keys, args);
        if (result == null || result != OK) {
            log.warn("refund {} restored in db but redis keys unavailable; reconcile will repair", refundId);
        }
    }

    public void setStock(long skuId, int sellable) {
        if (sellable < 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "库存数量不能为负");
        }
        transactionTemplate.executeWithoutResult(status -> {
            ProductStock row = stockMapper.selectForUpdate(skuId);
            String expected;
            if (row == null) {
                ProductStock created = new ProductStock();
                created.setSkuId(skuId);
                created.setAvailable(sellable);
                created.setReserved(0);
                stockMapper.insert(created);
                expected = "";
            } else {
                expected = String.valueOf(row.getAvailable() - row.getReserved());
                stockMapper.setSellable(skuId, sellable);
            }
            Long applied = redis.execute(scripts.setIfUnchanged,
                    List.of(StockLuaScripts.key(skuId)), expected, String.valueOf(sellable));
            if (applied == null || applied != OK) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "库存有在途变更，本次设置未生效，请稍后重试");
            }
        });
    }

    public List<StockDtos.StockView> stocks(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = skuIds.stream().distinct().toList();
        Map<Long, ProductStock> rows = stockMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(ProductStock::getSkuId, Function.identity()));
        List<StockDtos.StockView> views = new ArrayList<>(distinct.size());
        for (Long skuId : distinct) {
            ProductStock row = rows.get(skuId);
            views.add(new StockDtos.StockView(skuId,
                    row == null ? null : row.getAvailable() - row.getReserved()));
        }
        return views;
    }

    public List<StockDtos.StockView> allStocks() {
        return stockMapper.selectList(null).stream()
                .map(row -> new StockDtos.StockView(row.getSkuId(),
                        row.getAvailable() - row.getReserved()))
                .toList();
    }

    public StockDtos.StockView stock(long skuId) {
        ProductStock row = stockMapper.selectById(skuId);
        return new StockDtos.StockView(skuId,
                row == null ? null : row.getAvailable() - row.getReserved());
    }

    public void reconcile() {
        for (ProductStock row : stockMapper.selectList(null)) {
            String key = StockLuaScripts.key(row.getSkuId());
            long sellable = row.getAvailable() - row.getReserved();
            try {
                String value = redis.opsForValue().get(key);
                if (value == null) {
                    redis.opsForValue().setIfAbsent(key, String.valueOf(sellable));
                    log.warn("reconcile: rebuilt missing key {} from db", key);
                    continue;
                }
                long redisStock;
                try {
                    redisStock = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    log.warn("reconcile: malformed redis value '{}' for {}", value, key);
                    continue;
                }
                if (redisStock != sellable) {
                    log.warn("reconcile: sku {} redis={} db-sellable={}", row.getSkuId(),
                            redisStock, sellable);
                }
            } catch (RuntimeException e) {
                log.warn("reconcile: redis unavailable for {}: {}", key, e.toString());
                return;
            }
        }
    }

    private long executeReserve(long tradeId, List<StockDtos.StockItem> items) {
        List<String> keys = new ArrayList<>(items.size() + 1);
        for (StockDtos.StockItem item : items) {
            keys.add(StockLuaScripts.key(item.skuId()));
        }
        keys.add(StockLuaScripts.guardKey(tradeId));
        List<String> args = new ArrayList<>(items.size() + 2);
        args.add(StockLuaScripts.RESERVE_GUARD_TTL_SECONDS);
        args.add(String.valueOf(tradeId));
        for (StockDtos.StockItem item : items) {
            args.add(String.valueOf(item.quantity()));
        }
        Long result = redis.execute(scripts.reserve, keys, args.toArray());
        return result == null ? MISSING_KEY : result;
    }

    private void releaseRedis(long tradeId, List<StockDtos.StockItem> items) {
        List<String> keys = new ArrayList<>(items.size() + 1);
        for (StockDtos.StockItem item : items) {
            keys.add(StockLuaScripts.key(item.skuId()));
        }
        keys.add(StockLuaScripts.guardKey(tradeId));
        Object[] args = items.stream().map(item -> String.valueOf(item.quantity())).toArray();
        Long result = redis.execute(scripts.release, keys, args);
        if (result == null || (result != OK && result != ALREADY_RESERVED)) {
            log.warn("redis release for trade {} returned {}", tradeId, result);
        }
    }

    private void ensureStockKeys(List<StockDtos.StockItem> items) {
        if (items.isEmpty()) {
            return;
        }
        List<Long> skuIds = items.stream().map(StockDtos.StockItem::skuId).toList();
        Map<Long, ProductStock> rows = stockMapper.selectBatchIds(skuIds).stream()
                .collect(Collectors.toMap(ProductStock::getSkuId, Function.identity()));
        for (Long skuId : skuIds) {
            ProductStock row = rows.get(skuId);
            if (row == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "SKU " + skuId + " 未开通库存");
            }
            long sellable = row.getAvailable() - row.getReserved();
            redis.opsForValue().setIfAbsent(StockLuaScripts.key(skuId), String.valueOf(sellable));
        }
    }

    private static List<StockDtos.StockItem> normalize(List<StockDtos.StockItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "库存明细不能为空");
        }
        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (StockDtos.StockItem item : items) {
            if (item.quantity() == null || item.quantity() < 1) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "库存数量必须大于 0");
            }
            merged.merge(item.skuId(), item.quantity(), Integer::sum);
        }
        return merged.entrySet().stream()
                .map(entry -> new StockDtos.StockItem(entry.getKey(), entry.getValue()))
                .toList();
    }
}
