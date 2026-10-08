package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.order.service.OrderService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 延迟关单触发器：到达截止时间的 CREATED 订单会被关闭，
 * 并回滚其库存预占。已支付或已关闭的订单
 * 会被状态机守卫拒绝，因此重投递是安全的。
 */
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = OrderEventPublisher.TOPIC_TRADE,
        consumerGroup = "aurora-order-close-consumer",
        selectorExpression = OrderEventPublisher.TAG_ORDER_CLOSE_TIMEOUT)
public class OrderCloseListener implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(OrderCloseListener.class);

    private final OrderService orderService;

    public OrderCloseListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public void onMessage(String orderId) {
        long id;
        try {
            id = Long.parseLong(orderId);
        } catch (NumberFormatException e) {
            // 畸形 payload 重投无意义：记 error 后丢弃（与 PaymentRefundListener 一致）
            log.error("malformed close payload '{}' dropped", orderId);
            return;
        }
        orderService.closeIfPending(id);
    }
}
