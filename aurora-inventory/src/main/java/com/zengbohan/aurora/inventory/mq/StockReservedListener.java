package com.zengbohan.aurora.inventory.mq;

import com.zengbohan.aurora.common.mq.TradeTopics;
import com.zengbohan.aurora.inventory.stock.StockService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 消费订单事务消息发来的 stock-reserved 事件：
 * DB 账本追平 Redis 预占。去重与应用在 StockService 中
 * 共用同一事务，因此崩溃既不会重复应用，
 * 也不会丢失事件。
 */
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = TradeTopics.TOPIC_TRADE,
        consumerGroup = "aurora-inventory-stock-consumer",
        selectorExpression = TradeTopics.TAG_STOCK_RESERVED)
public class StockReservedListener implements RocketMQListener<StockReservedListener.StockReservedEvent> {

    // orderId 为对象类型：升级窗口内旧消息缺该字段时为 null，由 onMessage 回退。
    public record StockReservedEvent(String messageId, Long orderId, long skuId, int quantity) {
    }

    private final StockService stockService;

    public StockReservedListener(StockService stockService) {
        this.stockService = stockService;
    }

    @Override
    public void onMessage(StockReservedEvent event) {
        // 旧载荷没有 orderId：历史生产者固定 messageId = String.valueOf(orderId)，回退取之
        long orderId;
        if (event.orderId() != null) {
            orderId = event.orderId();
        } else {
            try {
                orderId = Long.parseLong(event.messageId());
            } catch (NumberFormatException e) {
                // 缺 orderId 且 messageId 不是数字：无法做关单对冲，丢弃防死信
                org.slf4j.LoggerFactory.getLogger(StockReservedListener.class)
                        .error("stock-reserved without orderId and unparseable messageId '{}'; dropped",
                                event.messageId());
                return;
            }
        }
        stockService.applyReservedEvent(event.messageId(), orderId, event.skuId(), event.quantity());
    }
}
