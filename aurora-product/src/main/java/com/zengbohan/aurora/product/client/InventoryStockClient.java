package com.zengbohan.aurora.product.client;

import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "aurora-inventory")
public interface InventoryStockClient {

    record StockRow(long skuId, Integer available) {
    }

    @GetMapping("/internal/stocks/batch")
    Result<List<StockRow>> batch(@RequestParam("skuIds") List<Long> skuIds);
}
