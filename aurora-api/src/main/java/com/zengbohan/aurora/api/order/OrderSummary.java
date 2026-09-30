package com.zengbohan.aurora.api.order;

import java.math.BigDecimal;

/**
 * 订单摘要（跨服务 wire 契约）：支付服务核单用的最小字段集。
 * 收敛自 payment 本地维护的 OrderClient.OrderInfo（M2 评审标记，M3 T8 收敛）。
 */
public record OrderSummary(long orderId, long userId, long skuId, int quantity,
        BigDecimal totalAmount, int status) {
}
