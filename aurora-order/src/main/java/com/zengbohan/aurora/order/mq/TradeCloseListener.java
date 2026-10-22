package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.common.mq.TradeTopics;
import com.zengbohan.aurora.order.service.TradeService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQMessageListener(topic = TradeTopics.TOPIC_TRADE,
        consumerGroup = "aurora-order-close-consumer",
        selectorExpression = TradeTopics.TAG_ORDER_CLOSE_TIMEOUT)
public class TradeCloseListener implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(TradeCloseListener.class);

    private final TradeService tradeService;

    public TradeCloseListener(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @Override
    public void onMessage(String payload) {
        long tradeId;
        try {
            tradeId = Long.parseLong(payload);
        } catch (NumberFormatException e) {
            log.error("malformed close payload '{}' dropped", payload);
            return;
        }
        try {
            tradeService.closeIfPending(tradeId);
        } catch (RuntimeException e) {
            log.error("close failed for trade {}; will retry", tradeId, e);
            throw e;
        }
    }
}
