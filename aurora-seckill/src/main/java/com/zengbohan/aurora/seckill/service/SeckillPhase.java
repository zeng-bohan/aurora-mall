package com.zengbohan.aurora.seckill.service;

import java.time.LocalDateTime;

/**
 * 活动阶段由时间窗推导，不落列：状态永远是时间的纯函数，没有「改状态」这个动作。
 */
public enum SeckillPhase {
    NOT_STARTED, RUNNING, ENDED;

    public static SeckillPhase of(LocalDateTime startAt, LocalDateTime endAt, LocalDateTime now) {
        if (now.isBefore(startAt)) {
            return NOT_STARTED;
        }
        return now.isAfter(endAt) ? ENDED : RUNNING;
    }
}
