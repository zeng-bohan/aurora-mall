package com.zengbohan.aurora.seckill.mq;

import com.zengbohan.aurora.seckill.service.SeckillOrderService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 异步落单消费者。幂等与失败语义都在 {@link SeckillOrderService#materialize} 里
 * （去重键 = 消息里的 messageId）；这里只做解包转发。
 */
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = SeckillTopics.TOPIC_SECKILL,
        consumerGroup = "aurora-seckill-order-consumer",
        selectorExpression = SeckillTopics.TAG_SECKILL_ORDER)
public class SeckillOrderListener implements RocketMQListener<SeckillOrderEvent> {

    private final SeckillOrderService orderService;

    public SeckillOrderListener(SeckillOrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public void onMessage(SeckillOrderEvent event) {
        orderService.materialize(event.messageId(), event.activityId(), event.userId());
    }
}
