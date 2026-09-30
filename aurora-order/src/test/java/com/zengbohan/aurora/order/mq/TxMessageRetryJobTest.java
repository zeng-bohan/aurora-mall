package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.order.entity.TxMessage;
import com.zengbohan.aurora.order.mapper.TxMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Local-message safety net: stale pending rows are resent then marked. */
class TxMessageRetryJobTest {

    private TxMessageMapper txMessageMapper;
    private OrderEventPublisher publisher;
    private TxMessageRetryJob job;

    @BeforeEach
    void setUp() {
        txMessageMapper = mock(TxMessageMapper.class);
        publisher = mock(OrderEventPublisher.class);
        job = new TxMessageRetryJob(txMessageMapper, publisher);
    }

    private TxMessage pending(long id, String bizKey) {
        TxMessage message = new TxMessage();
        message.setBizKey(bizKey);
        message.setTopic(OrderEventPublisher.TOPIC_TRADE);
        message.setTag(OrderEventPublisher.TAG_STOCK_RESERVED);
        message.setPayload("{\"messageId\":\"" + bizKey + "\"}");
        message.setStatus(TxMessage.STATUS_PENDING);
        message.setId(id);
        return message;
    }

    @Test
    void stalePendingMessagesAreResentAndMarkedSent() {
        TxMessage message = pending(11L, "1001");
        when(txMessageMapper.findStalePending(any(LocalDateTime.class))).thenReturn(List.of(message));

        job.resendStale();

        verify(publisher).resend(eq(OrderEventPublisher.TOPIC_TRADE),
                eq(OrderEventPublisher.TAG_STOCK_RESERVED), anyString(), eq("1001"));
        verify(txMessageMapper).markSent(11L);
    }

    @Test
    void nothingToResendMeansNoPublishCalls() {
        when(txMessageMapper.findStalePending(any(LocalDateTime.class))).thenReturn(List.of());

        job.resendStale();

        verify(publisher, never()).resend(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void poisonMessageDoesNotStarveTheRestOfTheSweep() {
        // 第一条 resend 抛异常（毒丸）：第二条仍被处理并标记
        TxMessage poison = pending(11L, "1001");
        TxMessage healthy = pending(12L, "1002");
        when(txMessageMapper.findStalePending(any(LocalDateTime.class)))
                .thenReturn(List.of(poison, healthy));
        org.mockito.Mockito.doThrow(new RuntimeException("mq down"))
                .when(publisher).resend(anyString(), anyString(), anyString(), eq("1001"));

        job.resendStale();

        verify(publisher).resend(anyString(), anyString(), anyString(), eq("1002"));
        verify(txMessageMapper).markSent(12L);
        verify(txMessageMapper, never()).markSent(11L);
    }
}
