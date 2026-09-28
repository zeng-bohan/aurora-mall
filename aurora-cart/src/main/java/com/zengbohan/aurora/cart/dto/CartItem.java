package com.zengbohan.aurora.cart.dto;

import java.math.BigDecimal;

public record CartItem(Long skuId, Integer quantity, String title, BigDecimal price,
                       Integer stock, Integer status) {
}
