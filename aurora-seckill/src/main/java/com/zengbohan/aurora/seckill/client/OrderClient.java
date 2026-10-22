package com.zengbohan.aurora.seckill.client;

import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@FeignClient(name = "aurora-order")
public interface OrderClient {

    record PlaceSeckillRequest(long userId, long activityId, long skuId,
                               BigDecimal price, String idempotencyKey) {
    }

    @PostMapping("/internal/trades/seckill")
    Result<Long> place(@RequestBody PlaceSeckillRequest request);

    @GetMapping("/internal/trades/seckill")
    Result<Long> find(@RequestParam("activityId") long activityId,
                      @RequestParam("userId") long userId);
}
