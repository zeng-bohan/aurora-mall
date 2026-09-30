package com.zengbohan.aurora.payment.client;

import com.zengbohan.aurora.api.order.OrderSummary;
import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;


/** Service-to-service order lookup (internal secret protects the path). */
@FeignClient(name = "aurora-order")
public interface OrderClient {


    @GetMapping("/internal/orders/{id}")
    Result<OrderSummary> byId(@PathVariable("id") long id);
}
