package com.zengbohan.aurora.api.product;

import java.math.BigDecimal;

/**
 * 商品快照（跨服务 wire 契约）：购物车行项目与订单定价共用的最小字段集。
 * 收敛自 cart/order 各自维护的字段同构 record（M2 评审标记，M3 T8 收敛）。
 */
public record ProductSnapshot(Long id, String title, BigDecimal price, Integer stock, Integer status) {

    // status 字段取值契约：1 = 在售，0 = 下架。
    public static final int STATUS_ON_SALE = 1;
    public static final int STATUS_OFF_SHELF = 0;
}
