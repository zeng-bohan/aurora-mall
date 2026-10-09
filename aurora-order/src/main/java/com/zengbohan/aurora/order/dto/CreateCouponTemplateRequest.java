package com.zengbohan.aurora.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运营建券请求体。只做透传：字段校验在 {@code CouponService}（任何调用方都过同一套规则）。
 */
public record CreateCouponTemplateRequest(String title,
                                          BigDecimal thresholdAmount,
                                          BigDecimal discountAmount,
                                          int total,
                                          LocalDateTime claimStartAt,
                                          LocalDateTime claimEndAt,
                                          int validDays) {
}
