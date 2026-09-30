package com.zengbohan.aurora.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengbohan.aurora.api.order.OrderSummary;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.payment.client.OrderClient;
import com.zengbohan.aurora.payment.entity.PaymentOrder;
import com.zengbohan.aurora.payment.mapper.PaymentOrderMapper;
import com.zengbohan.aurora.payment.mq.PaymentEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Mock payment channel: initiate creates one payment order per trade order
 * (the unique index both deduplicates concurrent initiations and anchors the
 * replay story); the mock callback is the "third-party" async notification.
 * A callback for an already-PAID order re-publishes the paid event - that
 * makes the callback endpoint itself the recovery channel when the first
 * publish was lost, and both consumers are idempotent.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentOrderMapper paymentOrderMapper;
    private final OrderClient orderClient;
    private final PaymentEventPublisher publisher;
    private final com.zengbohan.aurora.payment.channel.ChannelSignatureVerifier channelSignatureVerifier;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentOrderMapper paymentOrderMapper, OrderClient orderClient,
                          PaymentEventPublisher publisher, ObjectMapper objectMapper,
                          com.zengbohan.aurora.payment.channel.ChannelSignatureVerifier channelSignatureVerifier) {
        this.paymentOrderMapper = paymentOrderMapper;
        this.orderClient = orderClient;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.channelSignatureVerifier = channelSignatureVerifier;
    }

    /** Idempotent: the same trade order always maps to the same payment order. */
    public PaymentOrder initiate(long userId, long orderId) {
        PaymentOrder existing = paymentOrderMapper.findByOrderId(orderId);
        if (existing != null) {
            requireOwner(userId, orderId);
            return existing;
        }
        OrderSummary order = loadPayableOrder(orderId);
        if (order.userId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        PaymentOrder created = new PaymentOrder();
        created.setOrderId(orderId);
        created.setAmount(order.totalAmount());
        created.setStatus(PaymentOrder.STATUS_PAYING);
        try {
            paymentOrderMapper.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            // concurrent initiation raced us; the unique index decided
            return paymentOrderMapper.findByOrderId(orderId);
        }
    }

    /**
     * Mock third-party async callback. First delivery flips PAYING -> PAID
     * and publishes; repeat deliveries re-publish (consumer-side idempotency
     * makes it safe) so a lost publish is recoverable by replaying the
     * callback. Unknown orders are refused.
     */
    public PaymentOrder handleMockCallback(long orderId, java.math.BigDecimal amount,
                                           String channelSignature) {
        // 渠道签名先行（覆盖 orderId+amount，防金额篡改）：伪造回调在触达任何业务
        // 逻辑/数据之前 401
        if (!channelSignatureVerifier.isValid(orderId, amount.toPlainString(), channelSignature)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "渠道签名校验失败");
        }
        PaymentOrder payment = paymentOrderMapper.findByOrderId(orderId);
        if (payment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        // 迟到回调：关单赢了竞态后钱才到。mock 通道语义=自动退款，
        // 不发布 order-paid（否则"钱收了、单关了、库存回了"且无补偿）
        OrderSummary order = loadOrder(orderId);
        if (order != null && order.status() == 2) {
            paymentOrderMapper.markRefunded(orderId);
            log.warn("late callback for closed order {} — mock channel auto-refunds", orderId);
            return paymentOrderMapper.findByOrderId(orderId);
        }
        boolean first = paymentOrderMapper.markPaid(orderId) > 0;
        // 永远重读：并发回调/竞态下方法开头的快照可能已过期（用过期对象会误判 500）
        PaymentOrder current = paymentOrderMapper.findByOrderId(orderId);
        if (current == null || current.getStatus() != PaymentOrder.STATUS_PAID) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "支付状态异常");
        }
        if (!first) {
            log.info("duplicate callback for order {}; re-publishing paid event", orderId);
        }
        // markPaid 赢了竞态也要复检：关单可能恰好在其前后提交——
        // 钱收了但单已关 → 自动退款，不发 order-paid（对齐迟到回调语义）
        OrderSummary afterWin = loadOrder(orderId);
        if (afterWin != null && afterWin.status() == 2) {
            paymentOrderMapper.markRefunded(orderId);
            log.warn("close won the race for order {} — payment auto-refunds after markPaid", orderId);
            return paymentOrderMapper.findByOrderId(orderId);
        }
        publishPaid(current);
        return current;
    }

    public PaymentOrder byOrderId(long userId, long orderId) {
        PaymentOrder payment = paymentOrderMapper.findByOrderId(orderId);
        if (payment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        // 归属校验：非本人统一 NOT_FOUND，不泄露资源存在性（防 IDOR 枚举）
        requireOwner(userId, orderId);
        return payment;
    }

    private void requireOwner(long userId, long orderId) {
        OrderSummary order = loadOrder(orderId);
        if (order == null || order.userId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private OrderSummary loadPayableOrder(long orderId) {
        OrderSummary order = loadOrder(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (order.status() != 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单不可支付");
        }
        return order;
    }

    private OrderSummary loadOrder(long orderId) {
        Result<OrderSummary> result;
        try {
            result = orderClient.byId(orderId);
        } catch (RuntimeException e) {
            log.warn("order byId call failed for order {}", orderId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "订单服务不可用");
        }
        if (result == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "订单服务不可用");
        }
        if (result.code() == ErrorCode.NOT_FOUND.getCode()) {
            return null;
        }
        if (result.code() != ErrorCode.SUCCESS.getCode() || result.data() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "订单服务不可用");
        }
        return result.data();
    }

    /** 发送 order-paid 事件并打标；补发 job 复用同一构建路径，保证载荷形状一致。 */
    public void publishPaid(PaymentOrder payment) {
        OrderSummary order = loadOrder(payment.getOrderId());
        if (order == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "订单服务不可用");
        }
        if (order.status() == 2) {
            // 补发 job 的同源防线：订单已关的 PAID 记录不发事件，转退款
            paymentOrderMapper.markRefunded(payment.getOrderId());
            log.warn("skip paid event for closed order {} — auto-refunds", payment.getOrderId());
            return;
        }
        try {
            publisher.sendOrderPaid(String.valueOf(payment.getOrderId()),
                    objectMapper.writeValueAsString(new PaidEvent(
                            String.valueOf(payment.getOrderId()),
                            payment.getOrderId(), order.skuId(), order.quantity())));
            // 发出即打标：崩溃在 send 与 mark 之间时由 PaymentEventRetryJob 补发
            // （消费端 order/inventory 均幂等，重复投递安全）
            paymentOrderMapper.markEventPublished(payment.getOrderId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event serialization failed", e);
        }
    }

    /** Wire format consumed by aurora-order and aurora-inventory. */
    public record PaidEvent(String messageId, long orderId, long skuId, int quantity) {
    }

}
