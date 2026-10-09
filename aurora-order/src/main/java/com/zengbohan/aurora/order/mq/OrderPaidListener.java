package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.order.service.CouponService;
import com.zengbohan.aurora.order.service.OrderService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 支付成功 -> CREATED 订单转为 PAID。markPaid 是带守卫的
 * 状态迁移，因此重复投递与并发的关单
 * 最终恰好只有一个获胜者。
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
    private final CouponService couponService;

    public OrderPaidListener(OrderService orderService, OrderEventPublisher publisher,
                             CouponService couponService) {
        this.orderService = orderService;
        this.publisher = publisher;
        this.couponService = couponService;
    }

    @Override
    public void onMessage(OrderPaidEvent event) {
        boolean applied = orderService.markPaid(event.orderId());
        if (applied && couponService.markUsedForOrder(event.orderId())) {
            // 券核销跟着"订单转为 PAID"这一次迁移走（M5 S5）：无券订单是空操作，
            // 重投递时 markPaid 返回 false，这里也不会再动券
            log.info("coupon consumed by paid order {}", event.orderId());
        }
        if (!applied) {
            // 状态机拒绝：已支付（重放，无害）或已关单（迟到支付）。
            // 关单场景钱已收、库存已回滚——必须发退款信号闭环，不能静默丢弃
            if (orderService.isClosed(event.orderId())) {
                log.warn("late payment for CLOSED order {} — publishing refund signal", event.orderId());
                try {
                    publisher.publishRefundRequest(event.orderId());
                } catch (RuntimeException e) {
                    // 退款信号未发出绝不可静默丢弃：抛错让 order-paid 重投，
                    // markPaid 幂等、isClosed 判真，重投后重发同一信号
                    log.error("refund signal publish failed for order {}; will retry via redelivery",
                            event.orderId(), e);
                    throw e;
                }
            } else {
                log.info("order {} already paid (replay ignored)", event.orderId());
            }
        }
    }
}
