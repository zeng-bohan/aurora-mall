package com.zengbohan.aurora.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 我的券视图。status 取值 UNUSED / LOCKED / USED / EXPIRED——其中 EXPIRED 是读取时
 * 按 expireAt 推导的派生状态，不会出现在库里。
 */
public record CouponView(long id,
                         String title,
                         BigDecimal thresholdAmount,
                         BigDecimal discountAmount,
                         String status,
                         LocalDateTime expireAt,
                         Long orderId) {
}
