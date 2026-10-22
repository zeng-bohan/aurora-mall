package com.zengbohan.aurora.payment.mq;

import com.zengbohan.aurora.common.mq.TradeTopics;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final ObjectProvider<RocketMQTemplate> templateProvider;

    public PaymentEventPublisher(ObjectProvider<RocketMQTemplate> templateProvider) {
        this.templateProvider = templateProvider;
    }

    public void sendPaid(String messageId, String payload) {
        RocketMQTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("rocketmq not configured; paid event " + messageId + " was not sent");
        }
        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader(RocketMQHeaders.KEYS, messageId)
                .build();
        SendResult result = template.syncSend(
                TradeTopics.TOPIC_PAYMENT + ":" + TradeTopics.TAG_PAYMENT_PAID, message);
        if (result == null || result.getSendStatus() == null) {
            throw new IllegalStateException("paid event " + messageId + " returned no send status");
        }
        log.info("payment-paid event sent (msgId={}, key={}, status={})",
                result.getMsgId(), messageId, result.getSendStatus());
    }
}
