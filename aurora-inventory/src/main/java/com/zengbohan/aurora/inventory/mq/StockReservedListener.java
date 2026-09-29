package com.zengbohan.aurora.inventory.mq;

import com.zengbohan.aurora.inventory.stock.StockService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes stock-reserved events from the order transactional message: the
 * DB ledger catches up with the Redis reservation. Dedup + apply share one
 * transaction in StockService, so a crash can neither double-apply nor
 * lose the event.
 */
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = "aurora-trade",
        consumerGroup = "aurora-inventory-stock-consumer",
        selectorExpression = "tag-stock-reserved")
public class StockReservedListener implements RocketMQListener<StockReservedListener.StockReservedEvent> {

    public record StockReservedEvent(String messageId, long skuId, int quantity) {
    }

    private final StockService stockService;

    public StockReservedListener(StockService stockService) {
        this.stockService = stockService;
    }

    @Override
    public void onMessage(StockReservedEvent event) {
        stockService.applyReservedEvent(event.messageId(), event.skuId(), event.quantity());
    }
}
