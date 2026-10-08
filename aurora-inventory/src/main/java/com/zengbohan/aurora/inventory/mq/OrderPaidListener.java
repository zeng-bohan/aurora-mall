package com.zengbohan.aurora.inventory.mq;

import com.zengbohan.aurora.common.mq.TradeTopics;
import com.zengbohan.aurora.inventory.stock.StockService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 支付成功把预占转为真实扣减
 *（available 与 reserved 同时下降）。去重与应用共用同一事务；
 * 预占尚未落账时抛异常并触发重投递。
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
        stockService.applyPaidEvent(event.messageId(), event.orderId(), event.skuId(), event.quantity());
    }
}
