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

    /** 释放类请求带 orderId：库存侧按订单幂等（补偿可安全重入）。 */
    record ReleaseRequest(long orderId, int quantity) {
    }

    @PostMapping("/stocks/{skuId}/reserve")
    Result<Void> reserve(@PathVariable("skuId") long skuId, @RequestBody StockRequest request);

    @PostMapping("/stocks/{skuId}/rollback")
    Result<Void> rollback(@PathVariable("skuId") long skuId, @RequestBody ReleaseRequest request);

    /** AT comparison: db-only reservation inside the global transaction. */
    @PostMapping("/stocks/{skuId}/reserve-db")
    Result<Void> reserveDb(@PathVariable("skuId") long skuId, @RequestBody StockRequest request);

    /** AT order close: release the db reservation only (no redis was touched). */
    @PostMapping("/stocks/{skuId}/release-db")
    Result<Void> releaseDb(@PathVariable("skuId") long skuId, @RequestBody ReleaseRequest request);
}
