package com.zengbohan.aurora.seckill.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import com.zengbohan.aurora.seckill.mapper.SeckillActivityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀受理结果对账：把「投递结果未知 / 消费者崩溃 / 瞬时失败后无人重投」留下的
 * PENDING、PROCESSING 收敛成 ORDER 或 FAIL。
 * <p>
 * 只扫描结果 TTL 仍覆盖的活动（结束后一天内）。Redis 不可用时本轮直接放弃，
 * 下一轮继续；不能因为对账失败影响抢购主链路。
 */
@Component
@ConditionalOnProperty(name = "aurora.seckill.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class SeckillReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(SeckillReconcileJob.class);

    private final SeckillActivityMapper activityMapper;
    private final SeckillOrderService orderService;
    private final long pendingTimeoutMillis;
    private final long processingTimeoutMillis;

    public SeckillReconcileJob(SeckillActivityMapper activityMapper,
                               SeckillOrderService orderService,
                               @Value("${aurora.seckill.reconcile.pending-timeout-ms:120000}") long pendingTimeoutMillis,
                               @Value("${aurora.seckill.reconcile.processing-timeout-ms:300000}") long processingTimeoutMillis) {
        this.activityMapper = activityMapper;
        this.orderService = orderService;
        this.pendingTimeoutMillis = pendingTimeoutMillis;
        this.processingTimeoutMillis = processingTimeoutMillis;
    }

    @Scheduled(fixedDelayString = "${aurora.seckill.reconcile.interval-ms:30000}")
    public void reconcile() {
        List<SeckillActivity> activities;
        try {
            activities = activityMapper.selectList(Wrappers.<SeckillActivity>lambdaQuery()
                    .gt(SeckillActivity::getEndAt, LocalDateTime.now().minusDays(1)));
        } catch (RuntimeException e) {
            log.warn("seckill reconcile skipped: cannot list activities: {}", e.toString());
            return;
        }
        int changed = 0;
        for (SeckillActivity activity : activities) {
            try {
                changed += orderService.reconcile(activity, pendingTimeoutMillis, processingTimeoutMillis);
            } catch (RuntimeException e) {
                // 单个活动失败不阻塞其余活动；Redis 整体不可用时这里会连续失败，日志限频靠下一轮
                log.warn("seckill reconcile failed for activity {}: {}", activity.getId(), e.toString());
            }
        }
        if (changed > 0) {
            log.info("seckill reconcile settled {} stale reservation(s)", changed);
        }
    }
}
