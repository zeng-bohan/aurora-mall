package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.common.mq.TradeTopics;
import com.zengbohan.aurora.order.service.TradeService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(topic = TradeTopics.TOPIC_PAYMENT,
        consumerGroup = "aurora-order-payment-paid-consumer",
        selectorExpression = TradeTopics.TAG_PAYMENT_PAID)
public class PaymentPaidListener implements RocketMQListener<PaymentPaidListener.PaymentPaidEvent> {

    private static final Logger log = LoggerFactory.getLogger(PaymentPaidListener.class);

    public record PaymentPaidEvent(String messageId, long tradeId, BigDecimal amount) {
    }

    private final TradeService tradeService;

    public PaymentPaidListener(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @Override
    public void onMessage(PaymentPaidEvent event) {
        try {
            tradeService.markPaid(event.tradeId(), event.amount());
        } catch (RuntimeException e) {
            log.error("paid event failed for trade {}; will retry", event.tradeId(), e);
            throw e;
        }
    }
}
