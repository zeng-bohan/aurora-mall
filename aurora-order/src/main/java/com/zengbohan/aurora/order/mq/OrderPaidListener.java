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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrderPaidListener.class);

    public record OrderPaidEvent(String messageId, long orderId, long skuId, int quantity) {
    }

    private final OrderService orderService;
    private final OrderEventPublisher publisher;

    public OrderPaidListener(OrderService orderService, OrderEventPublisher publisher) {
        this.orderService = orderService;
        this.publisher = publisher;
    }

    @Override
    public void onMessage(OrderPaidEvent event) {
        boolean applied = orderService.markPaid(event.orderId());
        if (!applied) {
            // 状态机拒绝：已支付（重放，无害）或已关单（迟到支付）。
            // 关单场景钱已收、库存已回滚——必须发退款信号闭环，不能静默丢弃
            if (orderService.isClosed(event.orderId())) {
                log.warn("late payment for CLOSED order {} — publishing refund signal", event.orderId());
                publisher.publishRefundRequest(event.orderId());
            } else {
                log.info("order {} already paid (replay ignored)", event.orderId());
            }
        }
    }
}
