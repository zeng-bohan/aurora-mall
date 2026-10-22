package com.zengbohan.aurora.inventory.stock;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.common.web.RequireAdmin;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/stocks")
@RequireAdmin
public class AdminStockController {

    private final StockService stockService;

    public AdminStockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping("/{skuId}")
    public Result<StockDtos.StockView> get(@PathVariable long skuId) {
        return Result.ok(stockService.stock(skuId));
    }

    @PutMapping("/{skuId}")
    public Result<Void> set(@PathVariable long skuId,
                            @Valid @RequestBody StockDtos.SetStockRequest request) {
        stockService.setStock(skuId, request.quantity());
        return Result.ok();
    }

    @GetMapping
    public Result<List<StockDtos.StockView>> list(@RequestParam(required = false) List<Long> skuIds) {
        return Result.ok(skuIds == null || skuIds.isEmpty()
                ? stockService.allStocks() : stockService.stocks(skuIds));
    }

    @PostMapping("/reconcile")
    public Result<Integer> reconcile() {
        return Result.ok(stockService.rebuildRedisStock());
    }
}
