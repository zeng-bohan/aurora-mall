package com.zengbohan.aurora.order.mq;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.zengbohan.aurora.order.service.TradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "aurora.background-jobs.enabled", havingValue = "true", matchIfMissing = true)
public class AutoCompleteJob {

    private static final Logger log = LoggerFactory.getLogger(AutoCompleteJob.class);

    private final TradeService tradeService;

    public AutoCompleteJob(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @Scheduled(fixedDelayString = "${aurora.order.auto-complete-interval-ms:600000}", initialDelay = 120_000)
    public void completeShipped() {
        int changed = tradeService.autoComplete();
        if (changed > 0) {
            log.info("auto completed {} shipped trades", changed);
        }
    }
}
