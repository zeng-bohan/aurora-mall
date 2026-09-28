package com.zengbohan.aurora.cart.dto;

import java.math.BigDecimal;

/** Decoupled from the product entity: the cart only needs snapshot fields. */
public record ProductSnapshot(Long id, String title, BigDecimal price, Integer stock, Integer status) {
}
