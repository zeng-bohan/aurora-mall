package com.zengbohan.aurora.inventory.mq;

import com.zengbohan.aurora.common.mq.TradeTopics;
import com.zengbohan.aurora.inventory.stock.StockService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Payment success converts the reservation into a real deduction
 * (available and reserved both drop). Dedup + apply share one transaction;
 * a reservation that has not landed yet throws and is redelivered.
 */
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = TradeTopics.TOPIC_TRADE,
        consumerGroup = "aurora-inventory-paid-consumer",
        selectorExpression = TradeTopics.TAG_ORDER_PAID)
public class OrderPaidListener implements RocketMQListener<OrderPaidListener.OrderPaidEvent> {

    public record OrderPaidEvent(String messageId, long orderId, long skuId, int quantity) {
    }

    private final StockService stockService;

    public OrderPaidListener(StockService stockService) {
        this.stockService = stockService;
    }

    @Override
    public void onMessage(OrderPaidEvent event) {
        stockService.applyPaidEvent(event.messageId(), event.skuId(), event.quantity());
    }
}
