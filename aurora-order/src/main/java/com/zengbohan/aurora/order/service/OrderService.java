package com.zengbohan.aurora.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.idempotent.Idempotent;
import com.zengbohan.aurora.common.idempotent.Strategy;
import com.zengbohan.aurora.common.result.RemoteCall;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.id.SegmentIdGenerator;
import com.zengbohan.aurora.order.client.InventoryClient;
import com.zengbohan.aurora.order.dto.OrderView;
import com.zengbohan.aurora.order.dto.PlaceOrderRequest;
import com.zengbohan.aurora.order.entity.Order;
import com.zengbohan.aurora.order.entity.TxMessage;
import com.zengbohan.aurora.order.mapper.OrderMapper;
import com.zengbohan.aurora.order.mapper.TxMessageMapper;
import com.zengbohan.aurora.order.mq.OrderEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单生命周期（主链路）：幂等请求守卫 -> redis 预占库存 ->
 * 本地事务（order 与 tx_message 一起提交）-> 以 tx_message 为准确认
 * 事务消息 -> 延迟关单消息。每个失败窗口都有明确的兜底：
 * 预占失败就地回滚 redis 扣减，提交后的发送失败由 tx_message 重发 job
 * 捞出，关单消息丢失则由超时扫描兜底。
 */
@Service
@org.springframework.cloud.context.config.annotation.RefreshScope
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final SegmentIdGenerator idGenerator;
    private final ProductGuard productGuard;
    private final InventoryClient inventoryClient;
    private final OrderMapper orderMapper;
    private final TxMessageMapper txMessageMapper;
    private final TransactionTemplate transactionTemplate;
    private final OrderEventPublisher publisher;
    private final AtOrderPlacer atOrderPlacer;
    private final ObjectMapper objectMapper;

    private final int closeDelayLevel;
    private final long closeTimeoutSeconds;
    private final String txMode;

    public OrderService(ObjectMapper objectMapper,SegmentIdGenerator idGenerator,
                        ProductGuard productGuard,
                        InventoryClient inventoryClient,
                        OrderMapper orderMapper,
                        TxMessageMapper txMessageMapper,
                        TransactionTemplate transactionTemplate,
                        OrderEventPublisher publisher,
                        AtOrderPlacer atOrderPlacer,
                        @Value("${aurora.order.close-delay-level:16}") int closeDelayLevel,
                        @Value("${aurora.order.close-timeout-seconds:1800}") long closeTimeoutSeconds,
                        @Value("${aurora.tx.mode:mq}") String txMode,
                        @Value("${seata.enabled:false}") boolean seataEnabled) {
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.productGuard = productGuard;
        this.inventoryClient = inventoryClient;
        this.orderMapper = orderMapper;
        this.txMessageMapper = txMessageMapper;
        this.transactionTemplate = transactionTemplate;
        this.publisher = publisher;
        this.atOrderPlacer = atOrderPlacer;
        this.closeDelayLevel = closeDelayLevel;
        this.closeTimeoutSeconds = closeTimeoutSeconds;
        this.txMode = txMode;
        if (Order.TX_MODE_AT.equals(txMode) && !seataEnabled) {
            // mode=at 但未启用 seata starter 时，实际只会走普通本地事务，
            // 分支失败会留下已提交的订单——直接拒绝启动，而不是静默失败
            throw new IllegalStateException(
                    "aurora.tx.mode=at requires seata.enabled=true; otherwise the global transaction is inert"
                            + " and a failed branch leaves the order committed without rollback");
        }
    }

    // 只读访问器：供关单扫描 job 计算截止时间。
    public long closeTimeoutSeconds() {
        return closeTimeoutSeconds;
    }

    @Idempotent(strategy = Strategy.REDIS, key = "#userId + ':' + #idempotencyKey", ttlSeconds = 600)
    public long placeOrder(long userId, PlaceOrderRequest request, String idempotencyKey) {
        return placeOrder(userId, request, idempotencyKey, null);
    }

    /**
     * 带券下单（M5 S5）。券的判定与锁定通过 {@code couponHook} 回调进来，订单侧不认识券的
     * 任何类型或表：抵扣额在事务前算（纯函数），锁定在订单本地事务内做
     * （UNUSED→LOCKED 与订单行同生共死）。这样既不需要订单域持有券的数据访问，
     * 也不会出现"下单失败、券却被扣住"的中间态。
     * <p>
     * 两个入口都标 {@code @Idempotent}：内部委托调用不走代理，注解必须落在真正被调用的方法上。
     */
    @Idempotent(strategy = Strategy.REDIS, key = "#userId + ':' + #idempotencyKey", ttlSeconds = 600)
    public long placeOrder(long userId, PlaceOrderRequest request, String idempotencyKey,
                           CouponHook couponHook) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少 Idempotency-Key 请求头");
        }
        if (Order.TX_MODE_AT.equals(txMode)) {
            // Seata AT 对比场景；@GlobalTransactional 放在独立 Bean 的方法上，
            // 代理才能真正包裹它
            if (couponHook != null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "Seata AT 对照模式不支持用券");
            }
            return atOrderPlacer.placeAt(userId, request);
        }
        ProductSnapshot product = productGuard.load(request.skuId());
        BigDecimal baseAmount = product.price().multiply(BigDecimal.valueOf(request.quantity()));
        // 单次赋值：下面的本地事务 lambda 要捕获它（抵扣后金额必然为正——建券时已校验
        // 「抵扣额 < 门槛金额」，而门槛不满足会在 discountFor 里被拒）
        BigDecimal total = couponHook == null
                ? baseAmount
                : baseAmount.subtract(couponHook.discountFor(baseAmount));

        // 先生成 orderId：预扣按订单幂等，超时/重试/重放都不会重复扣减
        long orderId = idGenerator.nextId();
        // 第一步：Redis 预扣。失败即返回，无需补偿。
        reserveStock(orderId, request.skuId(), request.quantity());

        try {
            transactionTemplate.executeWithoutResult(status -> {
                Order order = new Order();
                order.setId(orderId);
                order.setUserId(userId);
                order.setSkuId(request.skuId());
                order.setQuantity(request.quantity());
                order.setTotalAmount(total);
                order.setStatus(Order.STATUS_CREATED);
                order.setTxMode(Order.TX_MODE_MQ);
                orderMapper.insert(order);
                if (couponHook != null) {
                    // 同一事务：券锁不住（并发被别单用掉）时订单也不落，
                    // 预扣由下面的 catch 统一归还
                    couponHook.bind(orderId);
                }

                TxMessage message = new TxMessage();
                message.setBizKey(String.valueOf(orderId));
                message.setTopic(OrderEventPublisher.TOPIC_TRADE);
                message.setTag(OrderEventPublisher.TAG_STOCK_RESERVED);
                message.setPayload(stockReservedPayload(orderId, request));
                message.setStatus(TxMessage.STATUS_PENDING);
                txMessageMapper.insert(message);
            });
        } catch (RuntimeException e) {
            // 本地事务没成：预扣必须还给用户，否则库存凭空少
            rollbackStockQuietly(orderId, request.skuId(), request.quantity(),
                    "local tx failed for order " + orderId);
            throw e;
        }

        // 提交之后的两条消息：任何一条失败都不回滚订单，
        // tx_message 兜底 job 重发事件、超时扫描兜底关单
        try {
            publisher.sendStockReservedTransactionally(String.valueOf(orderId), stockReservedPayload(orderId, request));
        } catch (RuntimeException e) {
            log.error("transactional event send failed for order {}; retry job will resend", orderId, e);
        }
        try {
            publisher.sendCloseTimeout(orderId, closeDelayLevel);
        } catch (RuntimeException e) {
            log.error("close-delay send failed for order {}; timeout scan will cover", orderId, e);
        }
        return orderId;
    }

    /** 下单用券钩子：券域实现（见 {@code CouponService.CouponUseHook}），订单域只认这两个动作。 */
    public interface CouponHook {

        /** 门槛判定 + 抵扣额（纯函数）；不满足门槛时抛业务码。 */
        BigDecimal discountFor(BigDecimal orderAmount);

        /** 在订单事务内把券锁到这张订单上；锁不住抛业务码，订单随事务回滚。 */
        void bind(long orderId);
    }

    public OrderView getOrder(long userId, long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (order.getUserId() != userId) {
            // 不暴露他人订单的存在性
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return toView(order);
    }

    // 供支付链路使用（T5）：CREATED -> PAID，重放幂等。
    public boolean markPaid(long orderId) {
        return orderMapper.transition(orderId, Order.STATUS_CREATED, Order.STATUS_PAID) > 0;
    }

    // 迟到支付判定：订单是否已关（关单赢了支付竞态）。
    public boolean isClosed(long orderId) {
        Order order = orderMapper.selectById(orderId);
        return order != null && order.getStatus() == Order.STATUS_CLOSED;
    }

    /**
     * 延迟消息、扫描与补偿的统一入口。幂等且可重入：CREATED -> CLOSED，
     * 随后释放库存，最后打 stock_released 标记。释放失败会抛异常且不落标记，
     * 于是重投递（MQ 重试）或补偿扫描会重新进入本方法把释放做完——
     * 已关闭的订单不可能永远锁着库存。已支付或已完全释放的订单落到空操作。
     */
    public boolean closeIfPending(long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            log.warn("close skipped: order {} not found", orderId);
            return false;
        }
        boolean newlyClosed = orderMapper.transition(orderId, Order.STATUS_CREATED, Order.STATUS_CLOSED) > 0;
        if (!newlyClosed) {
            if (order.getStatus() != Order.STATUS_CLOSED || order.isStockReleased()) {
                log.info("close skipped for order {} (not pending / already released)", orderId);
                return false;
            }
            log.info("order {} already closed but stock unreleased; compensating", orderId);
        }
        try {
            if (Order.TX_MODE_AT.equals(order.getTxMode())) {
                // AT 订单从未触碰 redis；只释放 DB 预占
                inventoryClient.releaseDb(order.getSkuId(),
                        new InventoryClient.ReleaseRequest(orderId, order.getQuantity()));
            } else {
                inventoryClient.rollback(order.getSkuId(),
                        new InventoryClient.ReleaseRequest(orderId, order.getQuantity()));
            }
        } catch (RuntimeException e) {
            log.error("stock release failed for order {}; redelivery or the compensation scan will retry", orderId, e);
            throw e;
        }
        orderMapper.markStockReleased(orderId);
        log.info("order {} closed, stock released", orderId);
        return newlyClosed;
    }

    private void reserveStock(long orderId, long skuId, int quantity) {
        Result<Void> result;
        try {
            result = RemoteCall.invoke("库存",
                    "order " + orderId + " sku " + skuId + " x" + quantity,
                    () -> inventoryClient.reserve(skuId, new InventoryClient.ReserveRequest(orderId, quantity)));
        } catch (BusinessException e) {
            // 传输失败/空响应 = 结果未知，预扣可能已经扣减。补偿按订单幂等，且库存侧只在
            // 「确实预扣过」时才回补，因此对从未落地的预扣也不会凭空加库存。
            if (e.getErrorCode() == ErrorCode.SYSTEM_ERROR) {
                compensateReserveQuietly(orderId, skuId, quantity);
            }
            throw e;
        }
        if (result.code() == ErrorCode.INVENTORY_INSUFFICIENT.getCode()) {
            throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT);
        }
        RemoteCall.requireSuccess(result, "库存");
    }

    private void rollbackStockQuietly(long orderId, long skuId, int quantity, String reason) {
        try {
            inventoryClient.rollback(skuId, new InventoryClient.ReleaseRequest(orderId, quantity));
        } catch (RuntimeException e) {
            // 补偿失败留给 inventory 对账任务兜底，但必须响亮
            log.error("compensation rollback failed for sku {} x{} ({})", skuId, quantity, reason, e);
        }
    }

    /** 预扣结果未知时的补偿：失败只记 ERROR（对外语义仍是"库存服务不可用"）。 */
    private void compensateReserveQuietly(long orderId, long skuId, int quantity) {
        try {
            inventoryClient.compensateReserve(skuId, new InventoryClient.ReleaseRequest(orderId, quantity));
        } catch (RuntimeException e) {
            log.error("reserve compensation failed for order {} (sku {} x{})",
                    orderId, skuId, quantity, e);
        }
    }

    private String stockReservedPayload(long orderId, PlaceOrderRequest request) {
        try {
            return objectMapper.writeValueAsString(new StockReservedEvent(
                    String.valueOf(orderId), orderId, request.skuId(), request.quantity()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event serialization failed", e);
        }
    }

    private OrderView toView(Order order) {
        return new OrderView(order.getId(), order.getUserId(), order.getSkuId(),
                order.getQuantity(), order.getTotalAmount(), order.getStatus());
    }

    // aurora-inventory 消费的 wire 格式。
    public record StockReservedEvent(String messageId, long orderId, long skuId, int quantity) {
    }
}
