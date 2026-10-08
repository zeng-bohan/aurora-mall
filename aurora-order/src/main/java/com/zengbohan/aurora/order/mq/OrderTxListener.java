package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.order.mapper.TxMessageMapper;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/**
 * 绑定到模板的生产者组（aurora-order-producer）。确认以本地 tx_message 行为锚：
 * 该行存在则提交，不存在则回滚。同一个查询也用于回答 broker 的回查，
 * 因此本地提交与确认之间发生崩溃时，最终恰好解析为一次 COMMIT。
 */
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
@RocketMQTransactionListener
public class OrderTxListener implements RocketMQLocalTransactionListener {

    private static final Logger log = LoggerFactory.getLogger(OrderTxListener.class);

    private final TxMessageMapper txMessageMapper;

    public OrderTxListener(TxMessageMapper txMessageMapper) {
        this.txMessageMapper = txMessageMapper;
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        String bizKey = arg != null ? arg.toString() : keyOf(message);
        return decide(bizKey);
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        return decide(keyOf(message));
    }

    private RocketMQLocalTransactionState decide(String bizKey) {
        boolean exists = txMessageMapper.findByBizKey(bizKey, OrderEventPublisher.TAG_STOCK_RESERVED) != null;
        log.info("tx confirmation for order {}: {}", bizKey, exists ? "COMMIT" : "ROLLBACK");
        return exists ? RocketMQLocalTransactionState.COMMIT : RocketMQLocalTransactionState.ROLLBACK;
    }

    private String keyOf(Message message) {
        Object key = message.getHeaders().get(RocketMQHeaders.KEYS);
        return key == null ? "" : key.toString();
    }
}
