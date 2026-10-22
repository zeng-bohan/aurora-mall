package com.zengbohan.aurora.inventory.stock;

import com.zengbohan.aurora.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/stocks")
public class InternalStockController {

    private final StockService stockService;

    public InternalStockController(StockService stockService) {
        this.stockService = stockService;
    }

    @PostMapping("/reserve")
    public Result<Void> reserve(@Valid @RequestBody StockDtos.StockBatchRequest request) {
        stockService.reserve(request.tradeId(), request.items());
        return Result.ok();
    }

    @PostMapping("/confirm")
    public Result<Void> confirm(@Valid @RequestBody StockDtos.StockBatchRequest request) {
        stockService.confirm(request.tradeId(), request.items());
        return Result.ok();
    }

    @PostMapping("/release")
    public Result<Void> release(@Valid @RequestBody StockDtos.StockBatchRequest request) {
        stockService.release(request.tradeId(), request.items());
        return Result.ok();
    }

    @PostMapping("/restore")
    public Result<Void> restore(@Valid @RequestBody StockDtos.RestoreRequest request) {
        stockService.restore(request.refundId(), request.tradeId(), request.items());
        return Result.ok();
    }

    @GetMapping("/batch")
    public Result<List<StockDtos.StockView>> batch(@RequestParam List<Long> skuIds) {
        return Result.ok(stockService.stocks(skuIds));
    }
}
