package com.zengbohan.aurora.seckill.mq;

import com.zengbohan.aurora.seckill.service.SeckillOrderService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 消费者只做解包转发：字段顺序/名字写错会让落单落到错误的活动或用户上，这里钉住映射；
 * 注解里的 topic/group/tag 写错则是"消息永远没人消费"这种静默故障，也一并钉住。
 */
class SeckillOrderListenerTest {

    private SeckillOrderService orderService;
    private SeckillOrderListener listener;

    @BeforeEach
    void setUp() {
        orderService = mock(SeckillOrderService.class);
        listener = new SeckillOrderListener(orderService);
    }

    @Test
    void eventIsForwardedFieldByField() {
        listener.onMessage(new SeckillOrderEvent("msg-9", 7L, 3L));

        verify(orderService).materialize("msg-9", 7L, 3L);
    }

    @Test
    void bindsToTheSameTopicAndTagAsThePublisher() {
        RocketMQMessageListener binding =
                SeckillOrderListener.class.getAnnotation(RocketMQMessageListener.class);

        assertThat(binding).isNotNull();
        assertThat(binding.topic()).isEqualTo(SeckillTopics.TOPIC_SECKILL);
        assertThat(binding.selectorExpression()).isEqualTo(SeckillTopics.TAG_SECKILL_ORDER);
        assertThat(binding.consumerGroup()).isEqualTo("aurora-seckill-order-consumer");
    }
}
