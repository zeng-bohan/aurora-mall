package com.zengbohan.aurora.order.mq;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.zengbohan.aurora.order.service.RefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "aurora.background-jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RefundSettlementJob {

    private static final Logger log = LoggerFactory.getLogger(RefundSettlementJob.class);

    private final RefundService refundService;

    public RefundSettlementJob(RefundService refundService) {
        this.refundService = refundService;
    }

    @Scheduled(fixedDelayString = "${aurora.order.refund-settle-interval-ms:60000}", initialDelay = 90_000)
    public void settlePending() {
        int changed = refundService.settlePending();
        if (changed > 0) {
            log.info("settled {} approved refunds", changed);
        }
    }
}
