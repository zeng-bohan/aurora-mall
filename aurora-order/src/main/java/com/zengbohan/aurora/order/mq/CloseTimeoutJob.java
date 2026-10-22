package com.zengbohan.aurora.order.mq;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.zengbohan.aurora.order.entity.TradeOrder;
import com.zengbohan.aurora.order.mapper.TradeOrderMapper;
import com.zengbohan.aurora.order.service.TradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "aurora.background-jobs.enabled", havingValue = "true", matchIfMissing = true)
public class CloseTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(CloseTimeoutJob.class);

    private final TradeOrderMapper tradeMapper;
    private final TradeService tradeService;

    public CloseTimeoutJob(TradeOrderMapper tradeMapper, TradeService tradeService) {
        this.tradeMapper = tradeMapper;
        this.tradeService = tradeService;
    }

    @Scheduled(fixedDelayString = "${aurora.order.close-scan-interval-ms:60000}", initialDelay = 75_000)
    public void closeOverdue() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(tradeService.closeTimeoutSeconds());
        for (TradeOrder overdue : tradeMapper.findTimedOut(deadline)) {
            try {
                tradeService.closeIfPending(overdue.getId());
            } catch (RuntimeException e) {
                log.error("timed-out close failed for trade {}", overdue.getId(), e);
            }
        }
        for (TradeOrder stranded : tradeMapper.findClosedUnreleased()) {
            try {
                tradeService.closeIfPending(stranded.getId());
            } catch (RuntimeException e) {
                log.error("compensating stock release failed for trade {}", stranded.getId(), e);
            }
        }
    }
}
