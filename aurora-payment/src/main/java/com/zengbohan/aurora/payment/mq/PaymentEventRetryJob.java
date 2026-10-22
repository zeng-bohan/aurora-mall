package com.zengbohan.aurora.payment.mq;

import com.zengbohan.aurora.payment.entity.PaymentOrder;
import com.zengbohan.aurora.payment.mapper.PaymentOrderMapper;
import com.zengbohan.aurora.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
public class PaymentEventRetryJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventRetryJob.class);

    private final PaymentOrderMapper paymentMapper;
    private final PaymentService paymentService;

    public PaymentEventRetryJob(PaymentOrderMapper paymentMapper, PaymentService paymentService) {
        this.paymentMapper = paymentMapper;
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelayString = "${aurora.payment.event-retry-interval-ms:60000}",
            initialDelay = 90_000)
    public void republishUnpublished() {
        for (PaymentOrder payment : paymentMapper.findUnpublishedPaid(LocalDateTime.now().minusSeconds(60))) {
            try {
                log.warn("payment for trade {} unpublished, resending paid event", payment.getTradeId());
                paymentService.publishPaid(payment);
            } catch (RuntimeException e) {
                paymentMapper.incrementPublishAttempts(payment.getTradeId());
                log.error("paid event republish failed for trade {}", payment.getTradeId(), e);
            }
        }
    }
}
