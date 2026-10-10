package com.zengbohan.aurora.order.client;

import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

// 订单生命周期使用的库存入口。
@FeignClient(name = "aurora-inventory")
public interface InventoryClient {

    record StockRequest(int quantity) {
    }

    // 预扣请求带 orderId：库存侧按订单幂等，重试/重投递不会重复扣减。
    record ReserveRequest(long orderId, int quantity) {
    }

    // 释放类请求带 orderId：库存侧按订单幂等（补偿可安全重入）。
    record ReleaseRequest(long orderId, int quantity) {
    }

    @PostMapping("/stocks/{skuId}/reserve")
    Result<Void> reserve(@PathVariable("skuId") long skuId, @RequestBody ReserveRequest request);

    @PostMapping("/stocks/{skuId}/rollback")
    Result<Void> rollback(@PathVariable("skuId") long skuId, @RequestBody ReleaseRequest request);

    // 预扣补偿：预扣调用结果未知（超时/断连）时的回补，库存侧只在确实预扣过时才加回。
    @PostMapping("/stocks/{skuId}/compensate-reserve")
    Result<Void> compensateReserve(@PathVariable("skuId") long skuId, @RequestBody ReleaseRequest request);

    // AT 对比：在全局事务内只做 DB 预占。
    @PostMapping("/stocks/{skuId}/reserve-db")
    Result<Void> reserveDb(@PathVariable("skuId") long skuId, @RequestBody StockRequest request);

    // AT 关单：只释放 DB 预占（从未触碰 redis）。
    @PostMapping("/stocks/{skuId}/release-db")
    Result<Void> releaseDb(@PathVariable("skuId") long skuId, @RequestBody ReleaseRequest request);
}
