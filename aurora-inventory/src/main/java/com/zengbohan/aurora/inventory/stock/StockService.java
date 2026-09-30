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
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;

    public StockService(StringRedisTemplate redis, StockLuaScripts scripts,
                        ProductStockMapper stockMapper, DedupStore dedupStore,
                        TransactionTemplate transactionTemplate) {
        this.redis = redis;
        this.scripts = scripts;
        this.stockMapper = stockMapper;
        this.dedupStore = dedupStore;
        this.transactionTemplate = transactionTemplate;
    }

    public void setStock(long skuId, int quantity) {
        if (quantity < 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "库存数量不能为负");
        }
        // 先 DB 后 Redis：两步之间崩溃时 key 缺失或仍是旧值，
        // 由 reserve 的 MISSING_KEY rebuild 与 reconcile 自愈；
        // 反序（先 Redis）会在窗口内留下"新 Redis + 旧 DB"且 key 存在，无自愈路径
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
        redis.opsForValue().set(StockLuaScripts.key(skuId), String.valueOf(quantity));
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
        // 二次仍 MISS（SETNX 竞态等极端情况）绝不能静默当作预扣成功——
        // redis 实际没扣而订单继续走，超卖从这里开始
        if (result == null || result == MISSING_KEY) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "库存服务异常");
        }
        if (result == INSUFFICIENT) {
            throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT);
        }
    }

    /**
     * Order-cancelled path: the redis sellable number goes back up AND the DB
     * reservation is released, so the two layers converge without waiting for
     * the reconcile job.
     * <p>
     * 双侧各自按 orderId 幂等：redis 用 SETNX 标记（恰好一次 INCRBY，挡住
     * MQ close listener 与超时扫描并发、以及"INCRBY 后、标记落库前崩溃"的
     * 重入窗口）；DB 用 dedup + 守卫式释放同一事务（崩溃回滚 dedup 行，
     * 重投递可重入）。任一侧缺失由重入补齐，两侧都不会多加。
     */
    public void rollback(long orderId, long skuId, int quantity) {
        if (quantity < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "回滚数量必须大于 0");
        }
        // redis 侧：marker 保证恰好一次 INCRBY
        redis.execute(scripts.rollback,
                List.of(StockLuaScripts.releasedMarkerKey(orderId), StockLuaScripts.key(skuId)),
                String.valueOf(quantity), StockLuaScripts.RELEASE_MARKER_TTL_SECONDS);
        // db 侧：dedup + 守卫式释放同一事务（崩溃回滚 dedup 行，重投递可重入）
        transactionTemplate.executeWithoutResult(status -> {
            if (!dedupStore.tryInsert("stock-release", String.valueOf(orderId))) {
                return;
            }
            if (stockMapper.decrementReserved(skuId, quantity) == 0) {
                log.warn("rollback: db reserved release skipped for sku {} x{} (event not yet applied?)",
                        skuId, quantity);
            }
        });
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

    /**
     * AT comparison path: DB-only reservation (no redis, no MQ). The guarded
     * UPDATE is the branch data seata rolls back through undo_log when the
     * global transaction fails.
     */
    public void reserveDb(long skuId, int quantity) {
        if (quantity < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "预扣数量必须大于 0");
        }
        if (stockMapper.reserveDbGuarded(skuId, quantity) == 0) {
            throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT);
        }
    }

    /** AT order close: release the db reservation only (no redis was touched).
     *  同样按 orderId 去重——关单补偿重入不能双扣 reserved。 */
    public void releaseDb(long orderId, long skuId, int quantity) {
        if (quantity < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "释放数量必须大于 0");
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (!dedupStore.tryInsert("stock-release-db", String.valueOf(orderId))) {
                return;
            }
            if (stockMapper.decrementReserved(skuId, quantity) == 0) {
                log.warn("releaseDb: nothing to release for sku {} x{}", skuId, quantity);
            }
        });
    }

    /**
     * Payment-confirmed ledger move: available and reserved both drop by the
     * quantity under the same transactional dedup as the reserve path. A
     * missing reservation is retried (reserved event may still be in flight);
     * the dedup row rolls back with the throw so the retry re-processes.
     */
    @Transactional
    public boolean applyPaidEvent(String messageId, long skuId, int quantity) {
        if (!dedupStore.tryInsert("order-paid", messageId)) {
            return false;
        }
        if (stockMapper.confirmPayment(skuId, quantity) == 0) {
            throw new IllegalStateException(
                    "reserved not applied yet for sku " + skuId + " x" + quantity + "; will retry");
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
        // SETNX: only fills a missing key. A blind SET here would clobber a
        // reservation that landed between the lua miss and this write.
        redis.opsForValue().setIfAbsent(StockLuaScripts.key(skuId), String.valueOf(sellable));
        log.info("rebuilt stock key for sku {} from db (sellable {})", skuId, sellable);
    }

    /** Delta log between redis and the db view; missing keys are rebuilt.
     *  A malformed value is warned and skipped - one bad key must not abort
     *  the whole sweep (the same defensive parse the cart store uses). */
    public void reconcile() {
        for (ProductStock row : stockMapper.selectList(null)) {
            String key = StockLuaScripts.key(row.getSkuId());
            String redisValue = redis.opsForValue().get(key);
            long dbSellable = row.getAvailable() - row.getReserved();
            if (redisValue == null) {
                redis.opsForValue().setIfAbsent(key, String.valueOf(dbSellable));
                log.warn("reconcile: rebuilt missing key {} from db", key);
                continue;
            }
            long redisStock;
            try {
                redisStock = Long.parseLong(redisValue);
            } catch (NumberFormatException e) {
                log.warn("reconcile: sku {} has malformed redis value '{}', skipping", row.getSkuId(), redisValue);
                continue;
            }
            if (redisStock != dbSellable) {
                log.warn("reconcile: sku {} redis={} db-sellable={} (in-flight reserved events explain this window)",
                        row.getSkuId(), redisStock, dbSellable);
            }
        }
    }
}
