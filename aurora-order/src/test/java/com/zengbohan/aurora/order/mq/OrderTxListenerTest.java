package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.order.entity.TxMessage;
import com.zengbohan.aurora.order.mapper.TxMessageMapper;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderTxListenerTest {

    private TxMessageMapper txMessageMapper;
    private OrderTxListener listener;

    @BeforeEach
    void setUp() {
        txMessageMapper = mock(TxMessageMapper.class);
        listener = new OrderTxListener(txMessageMapper);
    }

    private org.springframework.messaging.Message<String> messageWithKey(String key) {
        return MessageBuilder.withPayload("{}").setHeader(RocketMQHeaders.KEYS, key).build();
    }

    @Test
    void localCommitConfirmedWhenTxMessageExists() {
        when(txMessageMapper.findByBizKey("1001", OrderEventPublisher.TAG_STOCK_RESERVED))
                .thenReturn(new TxMessage());

        assertThat(listener.executeLocalTransaction(messageWithKey("1001"), "1001"))
                .isEqualTo(RocketMQLocalTransactionState.COMMIT);
    }

    @Test
    void localRollbackWhenLocalTransactionNeverCommitted() {
        when(txMessageMapper.findByBizKey(anyString(), anyString())).thenReturn(null);

        assertThat(listener.executeLocalTransaction(messageWithKey("1001"), "1001"))
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    @Test
    void brokerCheckBackResolvesFromTheSameAnchor() {
        when(txMessageMapper.findByBizKey("1002", OrderEventPublisher.TAG_STOCK_RESERVED))
                .thenReturn(new TxMessage());
        assertThat(listener.checkLocalTransaction(messageWithKey("1002")))
                .isEqualTo(RocketMQLocalTransactionState.COMMIT);

        when(txMessageMapper.findByBizKey("1003", OrderEventPublisher.TAG_STOCK_RESERVED))
                .thenReturn(null);
        assertThat(listener.checkLocalTransaction(messageWithKey("1003")))
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }
}
