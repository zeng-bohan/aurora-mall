package com.zengbohan.aurora.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengbohan.aurora.api.order.PaymentSummary;
import com.zengbohan.aurora.api.order.TradeSummary;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.RemoteCall;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.payment.channel.ChannelSignatureVerifier;
import com.zengbohan.aurora.payment.client.OrderClient;
import com.zengbohan.aurora.payment.entity.PaymentOrder;
import com.zengbohan.aurora.payment.entity.PaymentRefund;
import com.zengbohan.aurora.payment.mapper.PaymentOrderMapper;
import com.zengbohan.aurora.payment.mapper.PaymentRefundMapper;
import com.zengbohan.aurora.payment.mq.PaymentEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentOrderMapper paymentMapper;
    private final PaymentRefundMapper refundMapper;
    private final OrderClient orderClient;
    private final PaymentEventPublisher publisher;
    private final ChannelSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentOrderMapper paymentMapper, PaymentRefundMapper refundMapper,
                          OrderClient orderClient, PaymentEventPublisher publisher,
                          ChannelSignatureVerifier signatureVerifier, ObjectMapper objectMapper) {
        this.paymentMapper = paymentMapper;
        this.refundMapper = refundMapper;
        this.orderClient = orderClient;
        this.publisher = publisher;
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
    }

    public PaymentOrder initiate(long userId, long tradeId) {
        PaymentOrder existing = paymentMapper.findByTradeId(tradeId);
        if (existing != null) {
            if (existing.getUserId() != userId) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            return existing;
        }
        TradeSummary trade = loadTrade(tradeId);
        if (trade.userId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!TradeSummary.STATUS_CREATED.equals(trade.status())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单不可支付");
        }
        PaymentOrder created = new PaymentOrder();
        created.setTradeId(tradeId);
        created.setTradeNo(trade.tradeNo());
        created.setUserId(userId);
        created.setAmount(trade.payableAmount());
        created.setRefundedAmount(BigDecimal.ZERO.setScale(2));
        created.setStatus(PaymentOrder.STATUS_PAYING);
        created.setChannel("MOCK");
        created.setEventPublished(0);
        created.setPublishAttempts(0);
        try {
            paymentMapper.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            return paymentMapper.findByTradeId(tradeId);
        }
    }

    public PaymentOrder handleMockCallback(long tradeId, BigDecimal amount, String signature) {
        if (!signatureVerifier.isValid(tradeId, amount.toPlainString(), signature)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "渠道签名校验失败");
        }
        PaymentOrder payment = paymentMapper.findByTradeId(tradeId);
        if (payment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (payment.getAmount().compareTo(amount) != 0) {
            log.error("callback amount mismatch for trade {}: expected {}, got {}",
                    tradeId, payment.getAmount(), amount);
            throw new BusinessException(ErrorCode.PARAM_ERROR, "回调金额与订单金额不符");
        }
        TradeSummary trade = loadTrade(tradeId);
        if (TradeSummary.STATUS_CLOSED.equals(trade.status())) {
            paymentMapper.markRefundedLate(tradeId);
            log.warn("late callback for closed trade {} — mock channel auto-refunds", tradeId);
            return paymentMapper.findByTradeId(tradeId);
        }
        paymentMapper.markPaid(tradeId, LocalDateTime.now());
        PaymentOrder current = paymentMapper.findByTradeId(tradeId);
        if (current == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "支付状态异常");
        }
        if (PaymentOrder.STATUS_REFUNDED.equals(current.getStatus())) {
            return current;
        }
        TradeSummary afterWin = loadTrade(tradeId);
        if (TradeSummary.STATUS_CLOSED.equals(afterWin.status())) {
            paymentMapper.markRefundedLate(tradeId);
            log.warn("close won the race for trade {} — payment auto-refunds", tradeId);
            return paymentMapper.findByTradeId(tradeId);
        }
        publishPaid(current);
        return current;
    }

    public PaymentOrder byTradeId(long userId, long tradeId) {
        PaymentOrder payment = paymentMapper.findByTradeId(tradeId);
        if (payment == null || payment.getUserId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return payment;
    }

    /**
     * 浏览器内的模拟支付：由服务端按渠道密钥签名，再把同一笔回调交给
     * {@link #handleMockCallback}。密钥不出服务端，回调路径与真实渠道一致。
     */
    public PaymentOrder mockPay(long userId, long tradeId) {
        PaymentOrder payment = byTradeId(userId, tradeId);
        if (PaymentOrder.STATUS_REFUNDED.equals(payment.getStatus())) {
            return payment;
        }
        String signature = signatureVerifier.sign(tradeId, payment.getAmount().toPlainString());
        return handleMockCallback(tradeId, payment.getAmount(), signature);
    }

    public PaymentSummary internalRefund(long tradeId, long refundId, BigDecimal amount) {
        PaymentOrder payment = paymentMapper.findByTradeId(tradeId);
        if (payment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        PaymentRefund existing = refundMapper.selectById(refundId);
        if (existing != null) {
            return toSummary(paymentMapper.findByTradeId(tradeId));
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "退款金额必须大于 0");
        }
        PaymentRefund refund = new PaymentRefund();
        refund.setRefundId(refundId);
        refund.setTradeId(tradeId);
        refund.setAmount(amount);
        try {
            refundMapper.insert(refund);
        } catch (DuplicateKeyException e) {
            return toSummary(paymentMapper.findByTradeId(tradeId));
        }
        if (paymentMapper.applyRefund(tradeId, amount) == 0) {
            PaymentOrder current = paymentMapper.findByTradeId(tradeId);
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "支付单当前状态不可退款或可退金额不足");
        }
        return toSummary(paymentMapper.findByTradeId(tradeId));
    }

    public PaymentSummary lateRefund(long tradeId) {
        PaymentOrder payment = paymentMapper.findByTradeId(tradeId);
        if (payment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        paymentMapper.markRefundedLate(tradeId);
        return toSummary(paymentMapper.findByTradeId(tradeId));
    }

    public void publishPaid(PaymentOrder payment) {
        TradePaidEvent event = new TradePaidEvent("pay-" + payment.getId(),
                payment.getTradeId(), payment.getAmount());
        try {
            publisher.sendPaid(event.messageId(), objectMapper.writeValueAsString(event));
            paymentMapper.markEventPublished(payment.getTradeId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("paid event serialization failed", e);
        }
    }

    public PaymentSummary summary(long tradeId) {
        PaymentOrder payment = paymentMapper.findByTradeId(tradeId);
        if (payment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return toSummary(payment);
    }

    private TradeSummary loadTrade(long tradeId) {
        Result<TradeSummary> result = RemoteCall.invoke("订单", "trade " + tradeId,
                () -> orderClient.byId(tradeId));
        return RemoteCall.data(result, "订单");
    }

    private static PaymentSummary toSummary(PaymentOrder payment) {
        return new PaymentSummary(payment.getId(), payment.getTradeId(), payment.getTradeNo(),
                payment.getAmount(), payment.getRefundedAmount(), payment.getStatus());
    }

    public record TradePaidEvent(String messageId, long tradeId, BigDecimal amount) {
    }
}
