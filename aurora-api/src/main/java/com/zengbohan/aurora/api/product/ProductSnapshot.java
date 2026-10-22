package com.zengbohan.aurora.api.product;

import java.math.BigDecimal;

/**
 * 可售单元快照：购物车展示与订单定价共用的最小字段集。
 * attributesText 是已格式化的变体描述，例如「石墨黑 / 1 米」。
 */
public record ProductSnapshot(Long id, Long spuId, String skuCode, String title,
                              String attributesText, String imageUrl, BigDecimal price,
                              Integer stock, Integer status) {

    public static final int STATUS_ON_SALE = 1;
    public static final int STATUS_OFF_SHELF = 0;
}
