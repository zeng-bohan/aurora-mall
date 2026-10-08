package com.zengbohan.aurora.common.idempotent;

public enum Strategy {
    // Redis SETNX 守卫：重复调用以 DUPLICATE_REQUEST 快速失败。
    REDIS,
    // 去重表守卫：重复调用静默跳过（MQ 消费者）。
    DB_DEDUP
}
