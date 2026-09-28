package com.zengbohan.aurora.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CartItemRequest(
        @NotNull @Min(1) Long skuId,
        @NotNull @Min(1) Integer quantity) {
}
