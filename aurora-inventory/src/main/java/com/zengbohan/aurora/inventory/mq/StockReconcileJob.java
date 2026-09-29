package com.zengbohan.aurora.inventory.mq;

import com.zengbohan.aurora.inventory.stock.StockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic redis-vs-db reconciliation (ADR-0003 safety net). */
@Component
@ConditionalOnProperty(name = "rocketmq.name-server")
public class StockReconcileJob {

    private final StockService stockService;

    public StockReconcileJob(StockService stockService) {
        this.stockService = stockService;
    }

    @Scheduled(fixedDelayString = "${aurora.stock.reconcile-interval-ms:60000}", initialDelay = 30_000)
    public void reconcile() {
        stockService.reconcile();
    }
}
