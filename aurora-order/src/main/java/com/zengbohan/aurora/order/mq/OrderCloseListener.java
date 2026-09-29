package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.order.service.OrderService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The delayed close trigger: CREATED orders that reach their deadline are
 * closed and their stock reservation rolled back. Paid or already-closed
 * orders are rejected by the state machine guard, so redelivery is safe.
 */
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = OrderEventPublisher.TOPIC_TRADE,
        consumerGroup = "aurora-order-close-consumer",
        selectorExpression = OrderEventPublisher.TAG_ORDER_CLOSE_TIMEOUT)
public class OrderCloseListener implements RocketMQListener<String> {

    private final OrderService orderService;

    public OrderCloseListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public void onMessage(String orderId) {
        orderService.closeIfPending(Long.parseLong(orderId));
    }
}
