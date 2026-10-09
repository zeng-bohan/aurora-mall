package com.zengbohan.aurora.seckill.dto;

/**
 * 抢购结果视图。
 * <ul>
 *   <li>{@link #STATUS_PLACED}：已落单，orderId 非空（同步模式直接返回，异步模式由轮询查到）。</li>
 *   <li>{@link #STATUS_QUEUED}：已受理、异步落单中，orderId 为空；客户端凭
 *       {@code GET /activities/{id}/orders/mine} 轮询结果。</li>
 * </ul>
 */
public record SeckillBuyView(String status, Long orderId) {

    public static final String STATUS_PLACED = "PLACED";
    public static final String STATUS_QUEUED = "QUEUED";
}
