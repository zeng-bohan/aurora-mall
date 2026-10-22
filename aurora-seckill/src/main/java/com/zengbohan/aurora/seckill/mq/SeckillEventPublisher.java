package com.zengbohan.aurora.seckill.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 秒杀事件发送。模板惰性解析：上下文测试没有 name-server 也不会因此启动失败。
 * <p>
 * 发送结果有三种，调用方必须区别对待：
 * <ul>
 *   <li>{@link DispatchResult#ACCEPTED}：Broker 已接收。RocketMQ 的
 *       FLUSH_DISK_TIMEOUT / FLUSH_SLAVE_TIMEOUT / SLAVE_NOT_AVAILABLE 表示
 *       消息已到达 Broker、只是落盘或同步副本未在超时内确认，不能当成未发送；</li>
 *   <li>{@link DispatchResult#NOT_CONFIGURED}：没有 MQ 模板，确定一个字节也没发出去；</li>
 *   <li>{@link DispatchResult#UNKNOWN}：调用抛异常（含超时），Broker 可能已收到。
 *       这时既不能谎报成功，也不能立刻归还名额。</li>
 * </ul>
 */
@Component
public class SeckillEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SeckillEventPublisher.class);

    public enum DispatchResult {
        ACCEPTED, NOT_CONFIGURED, UNKNOWN
    }

    private final ObjectProvider<RocketMQTemplate> templateProvider;
    private final ObjectMapper objectMapper;

    public SeckillEventPublisher(ObjectProvider<RocketMQTemplate> templateProvider, ObjectMapper objectMapper) {
        this.templateProvider = templateProvider;
        this.objectMapper = objectMapper;
    }

    public DispatchResult sendOrderRequest(String messageId, long activityId, long userId) {
        RocketMQTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            log.warn("rocketmq not configured; seckill order request rejected (activity={}, user={})",
                    activityId, userId);
            return DispatchResult.NOT_CONFIGURED;
        }
        try {
            Message<String> message = MessageBuilder.withPayload(payloadOf(messageId, activityId, userId))
                    .setHeader(RocketMQHeaders.KEYS, messageId)
                    .build();
            SendResult result = template.syncSend(
                    SeckillTopics.TOPIC_SECKILL + ":" + SeckillTopics.TAG_SECKILL_ORDER, message);
            SendStatus status = result == null ? null : result.getSendStatus();
            if (status == SendStatus.SEND_OK || status == SendStatus.FLUSH_DISK_TIMEOUT
                    || status == SendStatus.FLUSH_SLAVE_TIMEOUT || status == SendStatus.SLAVE_NOT_AVAILABLE) {
                log.info("seckill order request accepted (activity={}, user={}, msgId={}, status={})",
                        activityId, userId, result.getMsgId(), status);
                return DispatchResult.ACCEPTED;
            }
            // 未知状态不能当成功，也不能当确定失败：交给对账任务收敛
            log.error("seckill order request returned unexpected status (activity={}, user={}, status={})",
                    activityId, userId, status);
            return DispatchResult.UNKNOWN;
        } catch (RuntimeException e) {
            log.error("seckill order request send outcome unknown (activity={}, user={}); leaving pending",
                    activityId, userId, e);
            return DispatchResult.UNKNOWN;
        }
    }

    private String payloadOf(String messageId, long activityId, long userId) {
        try {
            return objectMapper.writeValueAsString(new SeckillOrderEvent(messageId, activityId, userId));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("seckill order event serialization failed", e);
        }
    }
}
