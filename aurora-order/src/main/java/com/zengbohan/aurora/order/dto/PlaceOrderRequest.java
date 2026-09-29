package com.zengbohan.aurora.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PlaceOrderRequest(
        @NotNull @Min(1) Long skuId,
        @NotNull @Min(1) Integer quantity) {
}
