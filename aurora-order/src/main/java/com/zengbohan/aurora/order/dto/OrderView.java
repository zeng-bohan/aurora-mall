package com.zengbohan.aurora.order.dto;

import java.math.BigDecimal;

public record OrderView(long orderId, long userId, long skuId, int quantity,
                        BigDecimal totalAmount, int status) {
}
