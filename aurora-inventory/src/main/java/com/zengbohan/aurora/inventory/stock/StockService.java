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
 * 双层库存：Redis 存可售数量（预占事件异步落库），MySQL 是账本。
 * 对账任务用 DB 视图（available - reserved）重建缺失的 key。
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);
    private static final long MISSING_KEY = -1L;
    private static final long INSUFFICIENT = -2L;
    private static final long ALREADY_RESERVED = -3L;

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
            // 即使在预占在途期间也保持"可售 == quantity"不变式：
            // available = quantity + reserved，原子完成
            stockMapper.resetAvailable(skuId, quantity);
        }
        redis.opsForValue().set(StockLuaScripts.key(skuId), String.valueOf(quantity));
    }

    /**
     * 按订单幂等的预扣：同一 orderId 的重试/重投递/响应丢失后的重放都不会重复扣减
     * （守卫由脚本原子创建，失败路径回滚守卫，后续合法重试照常进行）。
     */
    public void reserve(long orderId, long skuId, int quantity) {
        if (quantity < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "预扣数量必须大于 0");
        }
        Long result = reserveOnce(orderId, skuId, quantity);
        if (result != null && result == MISSING_KEY) {
            rebuildKeyFromDb(skuId);
            result = reserveOnce(orderId, skuId, quantity);
        }
        if (result != null && result == ALREADY_RESERVED) {
            // 幂等重放：本次没有扣减，也不算失败
            log.info("reserve for order {} skipped (already reserved)", orderId);
            return;
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

    private Long reserveOnce(long orderId, long skuId, int quantity) {
        return redis.execute(scripts.reserve,
                List.of(StockLuaScripts.key(skuId), StockLuaScripts.reserveGuardKey(orderId)),
                String.valueOf(quantity), StockLuaScripts.RESERVE_GUARD_TTL_SECONDS);
    }

    /**
     * 当前可售数量：{@code available - reserved}（DB 是账本，Redis 只是加速层，
     * 所以读账本而不是读缓存）。管理端用它显示真实可售量——商品表里的
     * {@code stock} 是另一个字段，两者不是一个东西。
     *
     * @return 该 SKU 还没有库存记录时返回 null，表示"未开通"而不是"卖完了"
     */
    public Integer sellableStock(long skuId) {
        ProductStock row = stockMapper.selectById(skuId);
        return row == null ? null : row.getAvailable() - row.getReserved();
    }

    /**
     * 预扣补偿：order 侧发起预扣但结果未知（超时/断连/空响应）时的安全回补。
     * <p>
     * 与 {@link #rollback(long, long, int)} 的安全前提不同：本方法只在「该订单确实预扣过」
     * （预扣守卫存在）时才把 redis 卖量加回去——对从未落地的预扣回补会凭空放大可售库存。
     * 释放标记与关单路径共用，两条路径之间同样不会重复回补；DB 侧不参与，因为结果未知时
     * 订单行没有建立，也就没有 stock-reserved 事件落账。
     *
     * @return true 表示本次确实补回了预扣
     */
    public boolean compensateReserve(long orderId, long skuId, int quantity) {
        if (quantity < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "补偿数量必须大于 0");
        }
        Long result = redis.execute(scripts.compensateReserve,
                List.of(StockLuaScripts.reserveGuardKey(orderId), StockLuaScripts.key(skuId),
                        StockLuaScripts.releasedMarkerKey(orderId)),
                String.valueOf(quantity), StockLuaScripts.RELEASE_MARKER_TTL_SECONDS);
        boolean compensated = result != null && result == 1L;
        if (compensated) {
            log.warn("compensated an unknown-outcome reserve for order {} (sku {} x{})",
                    orderId, skuId, quantity);
        }
        return compensated;
    }

    /**
     * 订单取消路径：Redis 可售数量回升，同时释放 DB 预占，两层无需等待
     * 对账任务即可自行收敛。
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
     * 把一条 stock-reserved 事件应用到 DB 账本。去重插入与原子更新在同一事务：
     * 两者之间崩溃会回滚去重行，因此重投递的消息一定会被处理，不会丢失。
     * reserved 计数走原子 SQL 更新——消费者之间不存在读-改-写竞态。
     *
     * @return 首次应用返回 true；重投递（被去重）返回 false
     */
    @Transactional
    public boolean applyReservedEvent(String messageId, long orderId, long skuId, int quantity) {
        if (!dedupStore.tryInsert("stock-reserved", messageId)) {
            return false;
        }
        if (stockMapper.incrementReserved(skuId, quantity) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        // 关单已赢竞态：release 的 decrement 因预扣未落库记账而落空，唯一对冲点
        // 在这里——本事件到达后补扣，reserved 恰好归零。exists 探测必须在
        // incrementReserved 之后（双方都先对 sku 行加写锁，后提交方的探测必见
        // 先提交方）。
        if (dedupStore.exists("stock-release", String.valueOf(orderId))) {
            int undone = stockMapper.decrementReserved(skuId, quantity);
            log.warn("stock-reserved for released order {} neutralized (compensated reserved back to 0, undone={})",
                    orderId, undone);
        }
        return true;
    }

    /**
     * AT 对比路径：只做 DB 预占（不用 redis、不用 MQ）。这条带守卫的 UPDATE
     * 就是 seata 在全局事务失败时通过 undo_log 回滚的分支数据。
     */
    public void reserveDb(long skuId, int quantity) {
        if (quantity < 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "预扣数量必须大于 0");
        }
        if (stockMapper.reserveDbGuarded(skuId, quantity) == 0) {
            throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT);
        }
    }

    /** AT 关单：只释放 DB 预占（从未触碰 redis）。
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
     * 支付确认后的账本迁移：available 与 reserved 同时减去 quantity，去重方式
     * 与预占路径一致（同一事务内）。预占尚未落账时抛异常触发重试（reserved
     * 事件可能仍在途）；去重行随异常回滚，重试会重新处理。
     */
    @Transactional
    public boolean applyPaidEvent(String messageId, long orderId, long skuId, int quantity) {
        if (!dedupStore.tryInsert("order-paid", messageId)) {
            return false;
        }
        // 关单赢竞态且预扣已被中和：reserved 已是 0，confirmPayment 永久失败。
        // 这类消息标记已处理直接消费，不能进入无限重投/DLQ（钱在支付侧走退款闭环）。
        if (dedupStore.exists("stock-release", String.valueOf(orderId))) {
            log.warn("order-paid for released order {} consumed without stock deduction", orderId);
            return true;
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
        // SETNX：只填补缺失的 key。此处若用无条件 SET，会覆盖在
        // lua 未命中与本次写入之间落下的预占。
        redis.opsForValue().setIfAbsent(StockLuaScripts.key(skuId), String.valueOf(sellable));
        log.info("rebuilt stock key for sku {} from db (sellable {})", skuId, sellable);
    }

    /** redis 与 DB 视图的差异日志；缺失的 key 会被重建。
     *  值格式错误只告警并跳过——单个坏 key 不能中断整轮扫描
     *  （与 cart store 相同的防御式解析）。 */
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
                // 在途 reserved 事件窗口是有界的且编排已可收敛；此处只告警不修复，
                // 漂移持续（跨多个 reconcile 周期）即为真实丢失/错账信号。
                log.warn("reconcile: sku {} redis={} db-sellable={} (bounded by in-flight reserved events unless persistent)",
                        row.getSkuId(), redisStock, dbSellable);
            }
        }
    }
}
