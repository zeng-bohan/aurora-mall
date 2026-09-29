package com.zengbohan.aurora.payment.client;

import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;

/** Service-to-service order lookup (internal secret protects the path). */
@FeignClient(name = "aurora-order")
public interface OrderClient {

    record OrderInfo(long orderId, long userId, long skuId, int quantity,
                     BigDecimal totalAmount, int status) {
    }

    @GetMapping("/internal/orders/{id}")
    Result<OrderInfo> byId(@PathVariable("id") long id);
}
