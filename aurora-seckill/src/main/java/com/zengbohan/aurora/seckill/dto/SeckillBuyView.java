package com.zengbohan.aurora.seckill.dto;

/**
 * 抢购结果视图。
 * PLACED：已生成父订单，tradeId 非空；QUEUED：已受理、异步落单中。
 */
public record SeckillBuyView(String status, Long tradeId) {

    public static final String STATUS_PLACED = "PLACED";
    public static final String STATUS_QUEUED = "QUEUED";
}
