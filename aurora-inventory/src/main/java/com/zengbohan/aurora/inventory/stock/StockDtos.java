package com.zengbohan.aurora.inventory.stock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class StockDtos {

    private StockDtos() {
    }

    public record StockItem(@NotNull @Min(1) Long skuId, @NotNull @Min(1) Integer quantity) {
    }

    public record StockBatchRequest(@NotNull @Min(1) Long tradeId,
                                    @NotEmpty @Valid List<StockItem> items) {
    }

    public record RestoreRequest(@NotNull @Min(1) Long refundId,
                                 @NotNull @Min(1) Long tradeId,
                                 @NotEmpty @Valid List<StockItem> items) {
    }

    public record StockView(long skuId, Integer available) {
    }

    public record SetStockRequest(@NotNull @Min(0) Integer quantity) {
    }
}
