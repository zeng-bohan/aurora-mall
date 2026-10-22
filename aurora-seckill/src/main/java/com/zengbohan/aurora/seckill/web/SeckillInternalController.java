package com.zengbohan.aurora.seckill.web;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.seckill.service.SeckillOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeckillInternalController {

    public record ReleaseRequest(@NotNull @Min(1) Long tradeId,
                                 @NotNull @Min(1) Long activityId,
                                 @NotNull @Min(1) Integer quantity,
                                 @NotBlank String reason) {
    }

    private final SeckillOrderService orderService;

    public SeckillInternalController(SeckillOrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/internal/seckill/releases")
    public Result<Void> release(@Valid @RequestBody ReleaseRequest request) {
        orderService.releaseForTrade(request.tradeId(), request.activityId(),
                request.quantity(), request.reason());
        return Result.ok();
    }
}
