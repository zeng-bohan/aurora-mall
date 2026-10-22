package com.zengbohan.aurora.order.mq;

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
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final ObjectProvider<RocketMQTemplate> templateProvider;

    public OrderEventPublisher(ObjectProvider<RocketMQTemplate> templateProvider) {
        this.templateProvider = templateProvider;
    }

    public boolean sendCloseTimeout(long tradeId, int delayLevel) {
        RocketMQTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            log.warn("rocketmq not configured; close-delay skipped for trade {}", tradeId);
            return false;
        }
        try {
            Message<String> message = MessageBuilder.withPayload(String.valueOf(tradeId))
                    .setHeader(RocketMQHeaders.KEYS, tradeId)
                    .build();
            SendResult result = template.syncSend(
                    TradeTopics.TOPIC_TRADE + ":" + TradeTopics.TAG_ORDER_CLOSE_TIMEOUT,
                    message, 3_000, delayLevel);
            log.info("close-delay sent for trade {} (delayLevel={}, msgId={})",
                    tradeId, delayLevel, result.getMsgId());
            return true;
        } catch (RuntimeException e) {
            log.error("close-delay send failed for trade {}; tx_message retry will resend", tradeId, e);
            return false;
        }
    }

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
