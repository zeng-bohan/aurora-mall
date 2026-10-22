package com.zengbohan.aurora.seckill.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发送侧的两条契约：
 * 载荷必须能原样反序列化成消费者的事件（字段写错会让消息永远消费不成对的应用），
 * 以及 MQ 不可用时必须返回"没投出去"——调用方据此归还预扣名额，谎报成功会白白占掉名额。
 */
class SeckillEventPublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void payloadRoundTripsIntoConsumerEventAndSetsMessageKey() throws Exception {
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        when(template.syncSend(anyString(), any(Message.class))).thenReturn(sendResult(SendStatus.SEND_OK));
        SeckillEventPublisher publisher = publisherWith(template);

        assertThat(publisher.sendOrderRequest("msg-1", 7L, 3L))
                .isEqualTo(SeckillEventPublisher.DispatchResult.ACCEPTED);

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(template).syncSend(
                eq(SeckillTopics.TOPIC_SECKILL + ":" + SeckillTopics.TAG_SECKILL_ORDER), sent.capture());

        SeckillOrderEvent event = objectMapper.readValue(
                sent.getValue().getPayload().toString(), SeckillOrderEvent.class);
        assertThat(event).isEqualTo(new SeckillOrderEvent("msg-1", 7L, 3L));
        assertThat(sent.getValue().getHeaders().get(RocketMQHeaders.KEYS))
                .as("broker 的消息 key 也用它，便于按单条消息排查")
                .isEqualTo("msg-1");
    }

    @Test
    void timeoutOnBrokerAckStillCountsAsAccepted() {
        // FLUSH_DISK_TIMEOUT 说明消息已到 Broker，只是刷盘确认超时：不能当成没发出去
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        when(template.syncSend(anyString(), any(Message.class)))
                .thenReturn(sendResult(SendStatus.FLUSH_DISK_TIMEOUT));
        SeckillEventPublisher publisher = publisherWith(template);

        assertThat(publisher.sendOrderRequest("msg-1", 7L, 3L))
                .isEqualTo(SeckillEventPublisher.DispatchResult.ACCEPTED);
    }

    @Test
    void exceptionLeavesOutcomeUnknownRatherThanNotSent() {
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        when(template.syncSend(anyString(), any(Message.class)))
                .thenThrow(new IllegalStateException("broker timeout"));
        SeckillEventPublisher publisher = publisherWith(template);

        assertThat(publisher.sendOrderRequest("msg-1", 7L, 3L))
                .as("超时可能已送达，不能立即补偿；由对账任务确认")
                .isEqualTo(SeckillEventPublisher.DispatchResult.UNKNOWN);
    }

    @Test
    void missingTemplateIsDefinitelyNotSent() {
        ObjectProvider<RocketMQTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        assertThat(new SeckillEventPublisher(provider, objectMapper).sendOrderRequest("msg-1", 7L, 3L))
                .isEqualTo(SeckillEventPublisher.DispatchResult.NOT_CONFIGURED);
    }

    private static SendResult sendResult(SendStatus status) {
        SendResult result = new SendResult();
        result.setSendStatus(status);
        result.setMsgId("broker-msg-id");
        return result;
    }

    private SeckillEventPublisher publisherWith(RocketMQTemplate template) {
        ObjectProvider<RocketMQTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return new SeckillEventPublisher(provider, objectMapper);
    }
}
