package com.zengbohan.aurora.payment.client;

import com.zengbohan.aurora.api.order.OrderSummary;
import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;


// 服务间的订单查询（该路径由内部密钥保护）。
@FeignClient(name = "aurora-order")
public interface OrderClient {


    @GetMapping("/internal/orders/{id}")
    Result<OrderSummary> byId(@PathVariable("id") long id);
}
