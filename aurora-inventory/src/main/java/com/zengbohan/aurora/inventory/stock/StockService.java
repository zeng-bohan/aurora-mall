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
            // Redis 已扣减但 DB 台账缺失（上次 DB 事务回滚且补偿失败）：
            // 用本次请求重建台账，而不是让这笔订单永久卡死。
            log.warn("reserve ledger missing for trade {}; rebuilding from request", tradeId);
            try {
                writeReservations(tradeId, normalized);
            } catch (RuntimeException e) {
                releaseRedisQuietly(tradeId, normalized, "reserve ledger rebuild failed");
                throw e;
            }
            return;
        }
        if (result == INSUFFICIENT) {
            throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT);
        }
        if (result != OK) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "库存服务异常");
        }
        try {
            writeReservations(tradeId, normalized);
        } catch (RuntimeException e) {
            releaseRedisQuietly(tradeId, normalized, "reserve ledger insert failed");
            throw e;
        }
    }

    private void writeReservations(long tradeId, List<StockDtos.StockItem> items) {
        transactionTemplate.executeWithoutResult(status -> {
            for (StockDtos.StockItem item : items) {
                if (reservationMapper.insertIfAbsent(tradeId, item.skuId(), item.quantity()) == 1) {
                    if (stockMapper.incrementReserved(item.skuId(), item.quantity()) == 0) {
                        throw new BusinessException(ErrorCode.NOT_FOUND, "SKU " + item.skuId() + " 未开通库存");
                    }
                }
            }
        });
    }

    public void release(long tradeId, List<StockDtos.StockItem> requested) {
        List<StockReservation> reservations = reservationMapper.findByTrade(tradeId);
        List<StockReservation> reserved = reservations.stream()
                .filter(r -> "RESERVED".equals(r.getStatus())).toList();
        if (!reserved.isEmpty()) {
            List<StockDtos.StockItem> items = reserved.stream()
                    .map(r -> new StockDtos.StockItem(r.getSkuId(), r.getQuantity())).toList();
            try {
                ensureStockKeys(items);
                releaseRedis(tradeId, items);
            } catch (RuntimeException e) {
                log.error("redis release failed for trade {}; keeping reservation for retry", tradeId, e);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "库存释放未完成，请稍后重试");
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
            return;
        }
        // 没有待释放的 DB 台账时仍要补偿 Redis：
        // 1) 台账已标记 RELEASED 但上次 Redis 释放失败 -> 用台账数量重试；
        // 2) 台账整体缺失（预扣后 DB 事务回滚）-> 用调用方传入的数量重试。
        // 守卫 key 保证重复调用不会二次加库存；已确认的预约不参与。
        List<StockReservation> released = reservations.stream()
                .filter(r -> "RELEASED".equals(r.getStatus())).toList();
        List<StockDtos.StockItem> items;
        if (!released.isEmpty()) {
            items = released.stream()
                    .map(r -> new StockDtos.StockItem(r.getSkuId(), r.getQuantity())).toList();
        } else if (reservations.isEmpty() && requested != null && !requested.isEmpty()) {
            items = normalize(requested);
        } else {
            return;
        }
        ensureStockKeys(items);
        releaseRedis(tradeId, items);
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
        List<StockDtos.StockItem> eligible = new ArrayList<>();
        transactionTemplate.executeWithoutResult(status -> {
            for (StockDtos.StockItem item : normalized) {
                StockReservation reservation = reservationMapper.find(tradeId, item.skuId());
                if (reservation != null && "RELEASED".equals(reservation.getStatus())) {
                    log.warn("refund {} targets released reservation ({}, {})",
                            refundId, tradeId, item.skuId());
                    continue;
                }
                eligible.add(item);
                if (restoreMapper.insertIfAbsent(refundId, item.skuId(), item.quantity()) == 0) {
                    continue;
                }
                if (reservation != null && "RESERVED".equals(reservation.getStatus())) {
                    if (stockMapper.confirmReservation(item.skuId(), item.quantity()) == 0) {
                        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                                "退款回补失败：预占数量不足");
                    }
                    reservationMapper.markConfirmed(tradeId, item.skuId());
                }
                if (stockMapper.restoreAvailable(item.skuId(), item.quantity()) == 0) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "SKU " + item.skuId() + " 未开通库存");
                }
            }
        });
        if (eligible.isEmpty()) {
            return;
        }
        // 回补按退款单落一次 Redis 守卫：DB 已提交但 Redis 失败时，结算重试会再次调用
        // 本方法，守卫保证不会把同一笔退款加两次。key 缺失时用 DB 当前可售量重建
        // （该值已包含本次回补），存在时才做增量。
        List<String> keys = new ArrayList<>(eligible.size() + 1);
        for (StockDtos.StockItem item : eligible) {
            keys.add(StockLuaScripts.key(item.skuId()));
        }
        keys.add(StockLuaScripts.restoreGuardKey(refundId));
        Map<Long, ProductStock> rows = stockMapper.selectBatchIds(
                        eligible.stream().map(StockDtos.StockItem::skuId).toList())
                .stream().collect(Collectors.toMap(ProductStock::getSkuId, Function.identity()));
        List<String> args = new ArrayList<>(eligible.size() * 2 + 2);
        for (StockDtos.StockItem item : eligible) {
            args.add(String.valueOf(item.quantity()));
        }
        args.add(String.valueOf(refundId));
        args.add(StockLuaScripts.RESTORE_GUARD_TTL_SECONDS);
        for (StockDtos.StockItem item : eligible) {
            ProductStock row = rows.get(item.skuId());
            int sellable = row == null ? 0 : row.getAvailable() - row.getReserved();
            args.add(String.valueOf(sellable));
        }
        Long result = redis.execute(scripts.refundRestore, keys, args.toArray());
        if (result == null || (result != OK && result != ALREADY_RESERVED)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "退款库存回补未同步到 Redis，等待重试");
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

    /**
     * 以 DB 台账为准重建 Redis 可售量。用于演示数据重置、Redis 丢键或人工对账修复；
     * 会覆盖差异键，执行时应避开正在预扣的订单。
     */
    public int rebuildRedisStock() {
        return rebuildRedisStock(null);
    }

    int rebuildRedisStock(List<Long> skuIds) {
        List<ProductStock> rows = skuIds == null
                ? stockMapper.selectList(null)
                : stockMapper.selectBatchIds(skuIds);
        int repaired = 0;
        for (ProductStock row : rows) {
            long sellable = row.getAvailable() - row.getReserved();
            String key = StockLuaScripts.key(row.getSkuId());
            String desired = String.valueOf(sellable);
            String current = redis.opsForValue().get(key);
            if (!desired.equals(current)) {
                redis.opsForValue().set(key, desired);
                repaired++;
            }
        }
        return repaired;
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
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "redis release for trade " + tradeId + " returned " + result);
        }
    }

    private void releaseRedisQuietly(long tradeId, List<StockDtos.StockItem> items, String reason) {
        try {
            releaseRedis(tradeId, items);
        } catch (RuntimeException e) {
            log.error("redis compensation failed for trade {} ({}); reservation remains for retry",
                    tradeId, reason, e);
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
