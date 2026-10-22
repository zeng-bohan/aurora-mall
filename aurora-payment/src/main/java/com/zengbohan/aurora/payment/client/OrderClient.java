package com.zengbohan.aurora.payment.client;

import com.zengbohan.aurora.api.order.TradeSummary;
import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "aurora-order")
public interface OrderClient {

    @GetMapping("/internal/trades/{id}")
    Result<TradeSummary> byId(@PathVariable("id") long id);
}
