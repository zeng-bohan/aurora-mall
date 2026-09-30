package com.zengbohan.aurora.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.idempotent.Idempotent;
import com.zengbohan.aurora.common.idempotent.Strategy;
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
 * Order lifecycle (ADR-0003 main path): idempotent request guard -> redis
 * stock reserve -> local transaction (order + tx_message together) ->
 * transactional message confirmed against tx_message -> delayed close
 * message. Every failure window has a named net: reserve failure rolls the
 * redis decrement back inline, a post-commit send failure is picked up by
 * the tx_message retry job, and a lost close message by the timeout scan.
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
    private final boolean seataEnabled;

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
        this.seataEnabled = seataEnabled;
        if ("at".equals(txMode) && !seataEnabled) {
            // mode=at without the seata starter enabled would run plain local
            // transactions and leave a committed order behind on branch
            // failure - refuse to boot instead of failing silently
            throw new IllegalStateException(
                    "aurora.tx.mode=at requires seata.enabled=true; otherwise the global transaction is inert"
                            + " and a failed branch leaves the order committed without rollback");
        }
    }

    /** Read-only accessor for the close scan job's deadline math. */
    public long closeTimeoutSeconds() {
        return closeTimeoutSeconds;
    }

    @Idempotent(strategy = Strategy.REDIS, key = "#userId + ':' + #idempotencyKey", ttlSeconds = 600)
    public long placeOrder(long userId, PlaceOrderRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少 Idempotency-Key 请求头");
        }
        if ("at".equals(txMode)) {
            // Seata AT comparison scenario; @GlobalTransactional lives on the
            // separate bean call so the proxy actually wraps it
            return atOrderPlacer.placeAt(userId, request);
        }
        ProductSnapshot product = productGuard.load(request.skuId());
        BigDecimal total = product.price().multiply(BigDecimal.valueOf(request.quantity()));

        // 第一步：Redis 预扣。失败即返回，无需补偿。
        reserveStock(request.skuId(), request.quantity());

        long orderId = idGenerator.nextId();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Order order = new Order();
                order.setId(orderId);
                order.setUserId(userId);
                order.setSkuId(request.skuId());
                order.setQuantity(request.quantity());
                order.setTotalAmount(total);
                order.setStatus(Order.STATUS_CREATED);
                order.setTxMode("mq");
                orderMapper.insert(order);

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

    /** Used by the payment flow (T5): CREATED -> PAID, idempotent on replay. */
    public boolean markPaid(long orderId) {
        return orderMapper.transition(orderId, Order.STATUS_CREATED, Order.STATUS_PAID) > 0;
    }

    /**
     * Delayed-message, scan and compensation entry point. Idempotent and
     * re-entrant: CREATED -> CLOSED, then the stock release, then a
     * stock_released marker. A release failure throws WITHOUT the marker, so
     * redelivery (MQ retry) or the compensation scan re-enters here and
     * finishes the release - a closed order can never keep the stock locked
     * forever. Paid or fully-released orders resolve to no-ops.
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
            if ("at".equals(order.getTxMode())) {
                // AT orders never touched redis; release the db reservation only
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

    private void reserveStock(long skuId, int quantity) {
        Result<Void> result;
        try {
            result = inventoryClient.reserve(skuId, new InventoryClient.StockRequest(quantity));
        } catch (RuntimeException e) {
            log.warn("inventory reserve call failed for sku {} x{}", skuId, quantity, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "库存服务不可用");
        }
        if (result != null && result.code() == ErrorCode.INVENTORY_INSUFFICIENT.getCode()) {
            throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT);
        }
        if (result == null || result.code() != ErrorCode.SUCCESS.getCode()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "库存服务不可用");
        }
    }

    private void rollbackStockQuietly(long orderId, long skuId, int quantity, String reason) {
        try {
            inventoryClient.rollback(skuId, new InventoryClient.ReleaseRequest(orderId, quantity));
        } catch (RuntimeException e) {
            // 补偿失败留给 inventory 对账任务兜底，但必须响亮
            log.error("compensation rollback failed for sku {} x{} ({})", skuId, quantity, reason, e);
        }
    }

    private String stockReservedPayload(long orderId, PlaceOrderRequest request) {
        try {
            return objectMapper.writeValueAsString(new StockReservedEvent(
                    String.valueOf(orderId), request.skuId(), request.quantity()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event serialization failed", e);
        }
    }

    private OrderView toView(Order order) {
        return new OrderView(order.getId(), order.getUserId(), order.getSkuId(),
                order.getQuantity(), order.getTotalAmount(), order.getStatus());
    }

    /** Wire format consumed by aurora-inventory. */
    public record StockReservedEvent(String messageId, long skuId, int quantity) {
    }
}
