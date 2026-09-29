package com.zengbohan.aurora.inventory.stock;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.idempotent.DedupStore;
import com.zengbohan.aurora.inventory.entity.ProductStock;
import com.zengbohan.aurora.inventory.mapper.ProductStockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Two-layer stock (ADR-0003): Redis is the sellable number (reserved events
 * land in the DB asynchronously), MySQL is the ledger. The reconcile job
 * restores missing keys from the DB view available - reserved.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);
    private static final long MISSING_KEY = -1L;
    private static final long INSUFFICIENT = -2L;

    private final StringRedisTemplate redis;
    private final StockLuaScripts scripts;
    private final ProductStockMapper stockMapper;
    private final DedupStore dedupStore;

    public StockService(StringRedisTemplate redis, StockLuaScripts scripts,
                        ProductStockMapper stockMapper, DedupStore dedupStore) {
        this.redis = redis;
        this.scripts = scripts;
        this.stockMapper = stockMapper;
        this.dedupStore = dedupStore;
    }

    public void setStock(long skuId, int quantity) {
        if (quantity < 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "库存数量不能为负");
        }
        redis.opsForValue().set(StockLuaScripts.key(skuId), String.valueOf(quantity));
        ProductStock row = stockMapper.selectById(skuId);
        if (row == null) {
            ProductStock created = new ProductStock();
            created.setSkuId(skuId);
            created.setAvailable(quantity);
            created.setReserved(0);
            stockMapper.insert(created);
        } else {
            // keep the invariant sellable == quantity even while reservations
            // are in flight: available = quantity + reserved, atomically
            stockMapper.resetAvailable(skuId, quantity);
        }
    }

    public void reserve(long skuId, int quantity) {
        if (quantity < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "预扣数量必须大于 0");
        }
        Long result = redis.execute(scripts.reserve, List.of(StockLuaScripts.key(skuId)), String.valueOf(quantity));
        if (result != null && result == MISSING_KEY) {
            rebuildKeyFromDb(skuId);
            result = redis.execute(scripts.reserve, List.of(StockLuaScripts.key(skuId)), String.valueOf(quantity));
        }
        if (result == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "库存服务异常");
        }
        if (result == INSUFFICIENT) {
            throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT);
        }
    }

    public void rollback(long skuId, int quantity) {
        if (quantity < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "回滚数量必须大于 0");
        }
        redis.execute(scripts.rollback, List.of(StockLuaScripts.key(skuId)), String.valueOf(quantity));
    }

    /**
     * Applies one stock-reserved event to the DB ledger. The dedup insert and
     * the atomic update share one transaction: a crash between them rolls the
     * dedup row back, so the redelivered message is processed, never lost.
     * The reserved counter moves via atomic SQL - no read-modify-write races
     * between consumers.
     *
     * @return true when first applied, false for a redelivered (deduped) message
     */
    @Transactional
    public boolean applyReservedEvent(String messageId, long skuId, int quantity) {
        if (!dedupStore.tryInsert("stock-reserved", messageId)) {
            return false;
        }
        if (stockMapper.incrementReserved(skuId, quantity) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return true;
    }

    public ProductStock dbStock(long skuId) {
        return stockMapper.selectById(skuId);
    }

    private void rebuildKeyFromDb(long skuId) {
        ProductStock row = stockMapper.selectById(skuId);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        long sellable = row.getAvailable() - row.getReserved();
        redis.opsForValue().set(StockLuaScripts.key(skuId), String.valueOf(sellable));
        log.info("rebuilt stock key for sku {} from db (sellable {})", skuId, sellable);
    }

    /** Delta log between redis and the db view; missing keys are rebuilt. */
    public void reconcile() {
        for (ProductStock row : stockMapper.selectList(null)) {
            String key = StockLuaScripts.key(row.getSkuId());
            String redisValue = redis.opsForValue().get(key);
            long dbSellable = row.getAvailable() - row.getReserved();
            if (redisValue == null) {
                redis.opsForValue().set(key, String.valueOf(dbSellable));
                log.warn("reconcile: rebuilt missing key {} from db", key);
            } else {
                long redisStock = Long.parseLong(redisValue);
                if (redisStock != dbSellable) {
                    log.warn("reconcile: sku {} redis={} db-sellable={} (in-flight reserved events explain this window)",
                            row.getSkuId(), redisStock, dbSellable);
                }
            }
        }
    }
}
