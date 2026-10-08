package com.zengbohan.aurora.api.order;

import java.math.BigDecimal;

/**
 * 订单摘要（跨服务 wire 契约）：支付服务核单用的最小字段集。
 * 收敛自 payment 本地维护的 OrderClient.OrderInfo（M2 评审标记，M3 T8 收敛）。
 */
public record OrderSummary(long orderId, long userId, long skuId, int quantity,
        BigDecimal totalAmount, int status) {

    // 订单状态三态（status 字段的取值契约，与 aurora-order 的 Order 实体及 DB 一致）。
    public static final int STATUS_CREATED = 0;
    public static final int STATUS_PAID = 1;
    public static final int STATUS_CLOSED = 2;
}
