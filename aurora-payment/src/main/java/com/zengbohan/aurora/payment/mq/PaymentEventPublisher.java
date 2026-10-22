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

// 把支付结果发送到交易 topic。
@Component
public class PaymentEventPublisher {

    public static final String TOPIC_TRADE = TradeTopics.TOPIC_TRADE;
    public static final String TAG_ORDER_PAID = TradeTopics.TAG_ORDER_PAID;

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final ObjectProvider<RocketMQTemplate> templateProvider;

    public PaymentEventPublisher(ObjectProvider<RocketMQTemplate> templateProvider) {
        this.templateProvider = templateProvider;
    }

    public void sendOrderPaid(String messageId, String payload) {
        RocketMQTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            // 不能静默返回：调用方会误以为已发送并打上 event_published，支付成功事件就永久丢了。
            // 抛出让补发 job 看到失败，继续重试。
            throw new IllegalStateException("rocketmq not configured; paid event " + messageId + " was not sent");
        }
        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader(RocketMQHeaders.KEYS, messageId)
                .build();
        SendResult result = template.syncSend(TOPIC_TRADE + ":" + TAG_ORDER_PAID, message);
        if (result == null || result.getSendStatus() == null) {
            throw new IllegalStateException("paid event " + messageId + " returned no send status");
        }
        log.info("order-paid event sent (msgId={}, key={}, status={})",
                result.getMsgId(), messageId, result.getSendStatus());
    }
}
