package com.zengbohan.aurora.inventory.stock;

import com.zengbohan.aurora.common.idempotent.Idempotent;
import com.zengbohan.aurora.common.idempotent.Strategy;
import com.zengbohan.aurora.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stocks")
public class StockController {

    /** set allows 0 (sell-out switch); reserve/rollback must be positive. */
    public record StockRequest(@Min(0) int quantity) {
    }

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    /** Internal seeding endpoint for newly created products. */
    @PutMapping("/{skuId}")
    public Result<Void> set(@PathVariable long skuId, @Valid @RequestBody StockRequest request) {
        stockService.setStock(skuId, request.quantity());
        return Result.ok();
    }

    @PostMapping("/{skuId}/reserve")
    public Result<Void> reserve(@PathVariable long skuId, @Valid @RequestBody StockRequest request) {
        stockService.reserve(skuId, request.quantity());
        return Result.ok();
    }

    @PostMapping("/{skuId}/rollback")
    public Result<Void> rollback(@PathVariable long skuId, @Valid @RequestBody StockRequest request) {
        stockService.rollback(skuId, request.quantity());
        return Result.ok();
    }
}
