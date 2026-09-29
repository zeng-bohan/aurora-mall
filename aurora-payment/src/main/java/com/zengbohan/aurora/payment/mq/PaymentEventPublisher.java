package com.zengbohan.aurora.payment.mq;

import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/** Emits payment results onto the trade topic. */
@Component
public class PaymentEventPublisher {

    public static final String TOPIC_TRADE = "aurora-trade";
    public static final String TAG_ORDER_PAID = "tag-order-paid";

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final ObjectProvider<RocketMQTemplate> templateProvider;

    public PaymentEventPublisher(ObjectProvider<RocketMQTemplate> templateProvider) {
        this.templateProvider = templateProvider;
    }

    public void sendOrderPaid(String messageId, String payload) {
        RocketMQTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            log.warn("rocketmq not configured; skipping paid event {}", messageId);
            return;
        }
        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader(RocketMQHeaders.KEYS, messageId)
                .build();
        SendResult result = template.syncSend(TOPIC_TRADE + ":" + TAG_ORDER_PAID, message);
        log.info("order-paid event sent (msgId={}, key={})", result.getMsgId(), messageId);
    }
}
