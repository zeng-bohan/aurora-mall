package com.zengbohan.aurora.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 券模板视图：用户看可领列表、运营看全量列表都用它，区别只在是否按领取窗口过滤。 */
public record CouponTemplateView(long id,
                                 String title,
                                 BigDecimal thresholdAmount,
                                 BigDecimal discountAmount,
                                 int remaining,
                                 LocalDateTime claimStartAt,
                                 LocalDateTime claimEndAt) {
}
