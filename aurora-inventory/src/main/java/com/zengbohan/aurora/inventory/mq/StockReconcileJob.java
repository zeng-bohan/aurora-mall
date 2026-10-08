package com.zengbohan.aurora.inventory.mq;

import com.zengbohan.aurora.inventory.stock.StockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 周期性的 redis 与 DB 对账（兜底）。
// 装配只看「本服务有没有 redis 与 DB」这一真实前提：此前挂在 rocketmq.name-server 上，
// 会在 MQ 配置缺失时被静默摘掉——而对账与 MQ 无关。
@Component
@ConditionalOnProperty(name = "aurora.stock.reconcile-enabled", havingValue = "true", matchIfMissing = true)
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
