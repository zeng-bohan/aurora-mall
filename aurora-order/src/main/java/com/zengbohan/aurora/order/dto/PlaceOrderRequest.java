package com.zengbohan.aurora.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PlaceOrderRequest(
        @NotNull @Min(1) Long skuId,
        @NotNull @Min(1) @Max(value = 999, message = "数量超出上限") Integer quantity) {
}
