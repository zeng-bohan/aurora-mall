package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.order.entity.TxMessage;
import com.zengbohan.aurora.order.mapper.TxMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Local-message safety net: any tx_message still pending after the grace
 * window gets resent as a plain message (consumer side is idempotent), then
 * marked sent. Covers a producer crash right after commit and any broker
 * confirmation loss.
 */
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
public class TxMessageRetryJob {

    private static final Logger log = LoggerFactory.getLogger(TxMessageRetryJob.class);

    private final TxMessageMapper txMessageMapper;
    private final OrderEventPublisher publisher;

    public TxMessageRetryJob(TxMessageMapper txMessageMapper, OrderEventPublisher publisher) {
        this.txMessageMapper = txMessageMapper;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${aurora.order.tx-retry-interval-ms:30000}", initialDelay = 45_000)
    public void resendStale() {
        for (TxMessage message : txMessageMapper.findStalePending(LocalDateTime.now().minusSeconds(60))) {
            try {
                log.warn("tx_message {} ({}) still pending, resending", message.getId(), message.getBizKey());
                publisher.resend(message.getTopic(), message.getTag(), message.getPayload(), message.getBizKey());
                txMessageMapper.markSent(message.getId());
            } catch (RuntimeException e) {
                // 毒丸隔离：一条重发失败不能饿死本轮后续消息
                log.error("tx_message resend failed for id {}", message.getId(), e);
            }
        }
    }
}
