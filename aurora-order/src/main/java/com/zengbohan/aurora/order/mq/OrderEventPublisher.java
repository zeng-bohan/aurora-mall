package com.zengbohan.aurora.order.mq;

import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Wraps RocketMQ sends behind one seam. The template resolves lazily: CI
 * context tests run without a name-server, sends log-and-skip there (the
 * local-message retry job covers the same ground in production).
 */
@Component
public class OrderEventPublisher {

    public static final String TOPIC_TRADE = "aurora-trade";
    public static final String TAG_STOCK_RESERVED = "tag-stock-reserved";
    public static final String TAG_ORDER_CLOSE_TIMEOUT = "tag-order-close-timeout";

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final ObjectProvider<RocketMQTemplate> templateProvider;

    public OrderEventPublisher(ObjectProvider<RocketMQTemplate> templateProvider) {
        this.templateProvider = templateProvider;
    }

    /** Half message; the transaction listener confirms against tx_message. */
    public void sendStockReservedTransactionally(String bizKey, String payload) {
        RocketMQTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            log.warn("rocketmq not configured; skipping transactional event for order {}", bizKey);
            return;
        }
        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader(RocketMQHeaders.KEYS, bizKey)
                .build();
        template.sendMessageInTransaction(TOPIC_TRADE + ":" + TAG_STOCK_RESERVED, message, bizKey);
    }

    /** Delayed close trigger; the timeout scan job is the safety net. */
    public void sendCloseTimeout(long orderId, int delayLevel) {
        RocketMQTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            log.warn("rocketmq not configured; skipping close-delay event for order {}", orderId);
            return;
        }
        Message<String> message = MessageBuilder.withPayload(String.valueOf(orderId))
                .setHeader(RocketMQHeaders.KEYS, orderId)
                .build();
        SendResult result = template.syncSend(TOPIC_TRADE + ":" + TAG_ORDER_CLOSE_TIMEOUT,
                message, 3_000, delayLevel);
        log.info("close-delay event sent for order {} (delayLevel={}, msgId={})",
                orderId, delayLevel, result.getMsgId());
    }

    /** Plain resend used by the local-message retry job. */
    public void resend(String topic, String tag, String payload, String bizKey) {
        RocketMQTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            log.warn("rocketmq not configured; retry send skipped for {}", bizKey);
            return;
        }
        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader(RocketMQHeaders.KEYS, bizKey)
                .build();
        template.syncSend(topic + ":" + tag, message);
    }
}
