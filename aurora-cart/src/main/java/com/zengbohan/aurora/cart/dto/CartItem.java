package com.zengbohan.aurora.cart.dto;

import java.math.BigDecimal;

public record CartItem(Long skuId, Long spuId, String skuCode, Integer quantity, String title,
                       String attributesText, String imageUrl, BigDecimal price,
                       Integer stock, Integer status) {
}
