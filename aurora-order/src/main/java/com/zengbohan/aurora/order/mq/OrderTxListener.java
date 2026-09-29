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
 * Bound to the template's producer group (aurora-order-producer). Confirmation
 * is anchored on the local tx_message row:
 * committed when the row exists, rolled back when it does not. The same
 * lookup answers broker check-backs, so a crash between local commit and
 * confirm resolves to COMMIT exactly once.
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
