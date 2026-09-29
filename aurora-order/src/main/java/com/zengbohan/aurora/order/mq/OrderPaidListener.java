package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.order.service.OrderService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Payment success -> CREATED orders move to PAID. markPaid is a guarded
 * transition, so duplicate deliveries and a racing close both resolve to
 * exactly one winner.
 */
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = OrderEventPublisher.TOPIC_TRADE,
        consumerGroup = "aurora-order-paid-consumer",
        selectorExpression = OrderEventPublisher.TAG_ORDER_PAID)
public class OrderPaidListener implements RocketMQListener<OrderPaidListener.OrderPaidEvent> {

    public record OrderPaidEvent(String messageId, long orderId, long skuId, int quantity) {
    }

    private final OrderService orderService;

    public OrderPaidListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public void onMessage(OrderPaidEvent event) {
        boolean applied = orderService.markPaid(event.orderId());
        if (!applied) {
            // state machine refused: already paid (replay) or closed (late payment)
            org.slf4j.LoggerFactory.getLogger(OrderPaidListener.class)
                    .info("order {} not marked paid (already paid or closed)", event.orderId());
        }
    }
}
