package com.zengbohan.aurora.api.order;

import java.math.BigDecimal;

public record PaymentSummary(long paymentId, long tradeId, String tradeNo,
                             BigDecimal amount, BigDecimal refundedAmount, String status) {

    public static final String STATUS_PAYING = "PAYING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED";
    public static final String STATUS_REFUNDED = "REFUNDED";
}
