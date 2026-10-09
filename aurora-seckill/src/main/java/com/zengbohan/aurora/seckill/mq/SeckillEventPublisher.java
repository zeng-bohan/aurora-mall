package com.zengbohan.aurora.seckill.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 秒杀事件发送。模板惰性解析：上下文测试没有 name-server 也不会因此启动失败。
 * <p>
 * 与订单模块的 publisher 不同——那里发送失败只记日志跳过（生产有本地消息重发 job 兜底），
 * 这里不行：预扣已经把名额扣掉了，静默丢弃等于白吃用户一个名额。所以发送结果要返回给
 * 调用方，由它归还名额。约定：<b>返回 false 表示"没有任何东西被受理"</b>，调用方必须补偿。
 */
@Component
public class SeckillEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SeckillEventPublisher.class);

    private final ObjectProvider<RocketMQTemplate> templateProvider;
    private final ObjectMapper objectMapper;

    public SeckillEventPublisher(ObjectProvider<RocketMQTemplate> templateProvider, ObjectMapper objectMapper) {
        this.templateProvider = templateProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 投递异步落单请求。
     *
     * @return true = 已投递；false = MQ 未配置或投递失败，调用方必须归还预扣名额
     */
    public boolean sendOrderRequest(String messageId, long activityId, long userId) {
        RocketMQTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            log.warn("rocketmq not configured; seckill order request rejected (activity={}, user={})",
                    activityId, userId);
            return false;
        }
        try {
            Message<String> message = MessageBuilder.withPayload(payloadOf(messageId, activityId, userId))
                    .setHeader(RocketMQHeaders.KEYS, messageId)
                    .build();
            SendResult result = template.syncSend(
                    SeckillTopics.TOPIC_SECKILL + ":" + SeckillTopics.TAG_SECKILL_ORDER, message);
            log.info("seckill order request sent (activity={}, user={}, msgId={})",
                    activityId, userId, result.getMsgId());
            return true;
        } catch (RuntimeException e) {
            log.error("seckill order request send failed (activity={}, user={})", activityId, userId, e);
            return false;
        }
    }

    private String payloadOf(String messageId, long activityId, long userId) {
        try {
            return objectMapper.writeValueAsString(new SeckillOrderEvent(messageId, activityId, userId));
        } catch (JsonProcessingException e) {
            // 三个字段的记录序列化不会失败；真失败说明环境坏了，交给上面的 catch 走补偿
            throw new IllegalStateException("seckill order event serialization failed", e);
        }
    }
}
