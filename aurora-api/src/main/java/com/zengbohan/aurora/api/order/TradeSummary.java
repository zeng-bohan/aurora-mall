package com.zengbohan.aurora.api.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * 父订单摘要：支付发起、回调核单与库存确认共用的跨服务契约。
 * 状态取值为字符串，避免各服务维护一份枚举副本。
 */
public record TradeSummary(long tradeId, String tradeNo, long userId, String source,
                           String status, BigDecimal payableAmount, BigDecimal refundedAmount,
                           List<TradeLine> items) {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_SHIPPED = "SHIPPED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    public record TradeLine(long skuId, long spuId, String title, int quantity,
                            BigDecimal unitPrice, BigDecimal itemAmount) {
    }
}
