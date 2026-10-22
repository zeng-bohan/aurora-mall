package com.zengbohan.aurora.inventory.stock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "aurora.background-jobs.enabled", havingValue = "true", matchIfMissing = true)
public class StockReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(StockReconcileJob.class);

    private final StockService stockService;

    public StockReconcileJob(StockService stockService) {
        this.stockService = stockService;
    }

    @Scheduled(fixedDelayString = "${aurora.stock.reconcile-interval-ms:60000}", initialDelay = 60_000)
    public void reconcile() {
        try {
            stockService.reconcile();
        } catch (RuntimeException e) {
            log.error("stock reconcile failed", e);
        }
    }
}
