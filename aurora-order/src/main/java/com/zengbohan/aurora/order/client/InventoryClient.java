package com.zengbohan.aurora.order.client;

import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Inventory entry points used by the order lifecycle. */
@FeignClient(name = "aurora-inventory")
public interface InventoryClient {

    record StockRequest(int quantity) {
    }

    @PostMapping("/stocks/{skuId}/reserve")
    Result<Void> reserve(@PathVariable("skuId") long skuId, @RequestBody StockRequest request);

    @PostMapping("/stocks/{skuId}/rollback")
    Result<Void> rollback(@PathVariable("skuId") long skuId, @RequestBody StockRequest request);
}
