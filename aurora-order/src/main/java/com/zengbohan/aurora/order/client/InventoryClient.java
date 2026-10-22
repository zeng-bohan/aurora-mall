package com.zengbohan.aurora.order.client;

import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = "aurora-inventory")
public interface InventoryClient {

    record StockItem(long skuId, int quantity) {
    }

    record StockBatchRequest(long tradeId, List<StockItem> items) {
    }

    record RestoreRequest(long refundId, long tradeId, List<StockItem> items) {
    }

    @PostMapping("/internal/stocks/reserve")
    Result<Void> reserve(@RequestBody StockBatchRequest request);

    @PostMapping("/internal/stocks/confirm")
    Result<Void> confirm(@RequestBody StockBatchRequest request);

    @PostMapping("/internal/stocks/release")
    Result<Void> release(@RequestBody StockBatchRequest request);

    @PostMapping("/internal/stocks/restore")
    Result<Void> restore(@RequestBody RestoreRequest request);
}
