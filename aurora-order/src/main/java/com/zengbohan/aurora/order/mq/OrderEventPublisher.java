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

/**
 * 把 RocketMQ 发送收敛在一处。模板是惰性解析的：CI 上下文测试没有
 * name-server，那里的发送只记日志并跳过（生产环境由本地消息重发 job 兜底）。
 */
@Component
public class OrderEventPublisher {

    // 事实源下沉到 common（消费者注解与生产者共用），保留旧名作委托以免扩散改动
    public static final String TOPIC_TRADE = TradeTopics.TOPIC_TRADE;
    public static final String TAG_STOCK_RESERVED = TradeTopics.TAG_STOCK_RESERVED;
    public static final String TAG_ORDER_CLOSE_TIMEOUT = TradeTopics.TAG_ORDER_CLOSE_TIMEOUT;
    public static final String TAG_ORDER_PAID = TradeTopics.TAG_ORDER_PAID;

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final ObjectProvider<RocketMQTemplate> templateProvider;

    public OrderEventPublisher(ObjectProvider<RocketMQTemplate> templateProvider) {
        this.templateProvider = templateProvider;
    }

    // 半消息；由事务监听器对照 tx_message 做确认。
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

    // 延迟关单触发器；超时扫描 job 是兜底。
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

    // 本地消息重发 job 使用的普通重发。
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

    // 迟到支付信号：通知 payment 对已关订单自动退款（payment 侧消费）。
    public void publishRefundRequest(long orderId) {
        RocketMQTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            log.warn("rocketmq not configured; refund signal skipped for order {}", orderId);
            return;
        }
        Message<String> message = MessageBuilder.withPayload(String.valueOf(orderId))
                .setHeader(RocketMQHeaders.KEYS, orderId)
                .build();
        template.syncSend(TOPIC_TRADE + ":" + TradeTopics.TAG_PAYMENT_REFUND, message);
        log.info("refund signal sent for order {}", orderId);
    }
}
