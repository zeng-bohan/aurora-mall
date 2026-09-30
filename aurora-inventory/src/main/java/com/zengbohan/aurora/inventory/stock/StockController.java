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

    /** 释放类请求：orderId 驱动库存侧幂等。 */
    public record ReleaseRequest(@Min(1) long orderId, @Min(0) int quantity) {
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

    /** AT 对照：DB 直接预占（由 order 的 @GlobalTransactional 包裹）。 */
    @PostMapping("/{skuId}/reserve-db")
    public Result<Void> reserveDb(@PathVariable long skuId, @Valid @RequestBody StockRequest request) {
        stockService.reserveDb(skuId, request.quantity());
        return Result.ok();
    }

    /** AT 对照：释放 DB 预占（关单路径，at 单专用，不动 redis）。按订单幂等。 */
    @PostMapping("/{skuId}/release-db")
    public Result<Void> releaseDb(@PathVariable long skuId, @Valid @RequestBody ReleaseRequest request) {
        stockService.releaseDb(request.orderId(), skuId, request.quantity());
        return Result.ok();
    }

    /** 关单回滚：redis +1 与 DB 释放均按订单幂等，补偿可安全重入。 */
    @PostMapping("/{skuId}/rollback")
    public Result<Void> rollback(@PathVariable long skuId, @Valid @RequestBody ReleaseRequest request) {
        stockService.rollback(request.orderId(), skuId, request.quantity());
        return Result.ok();
    }
}
