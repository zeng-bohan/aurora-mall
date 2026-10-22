package com.zengbohan.aurora.order.client;

import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "aurora-seckill")
public interface SeckillClient {

    record ReleaseRequest(long tradeId, long activityId, int quantity, String reason) {
    }

    @PostMapping("/internal/seckill/releases")
    Result<Void> release(@RequestBody ReleaseRequest request);
}
