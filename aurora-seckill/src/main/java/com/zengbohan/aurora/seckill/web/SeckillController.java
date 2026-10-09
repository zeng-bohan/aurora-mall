package com.zengbohan.aurora.seckill.web;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.seckill.service.SeckillActivityService;
import com.zengbohan.aurora.seckill.service.SeckillOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端：浏览秒杀活动与抢购。身份来自网关注入的 X-User-Id。
 */
@RestController
@RequestMapping("/activities")
public class SeckillController {

    private final SeckillActivityService activityService;
    private final SeckillOrderService orderService;

    public SeckillController(SeckillActivityService activityService, SeckillOrderService orderService) {
        this.activityService = activityService;
        this.orderService = orderService;
    }

    @GetMapping
    public Result<List<SeckillActivityService.SeckillActivityView>> list() {
        return Result.ok(activityService.listNotEndedViews());
    }

    @GetMapping("/{id}")
    public Result<SeckillActivityService.SeckillActivityView> detail(@PathVariable long id) {
        return Result.ok(activityService.viewOf(id));
    }

    // 抢购：先过 Redis 预扣闸门，抢到名额才落单（S2-1 同步；S2-2 换成异步落单后接口语义不变）
    @PostMapping("/{id}/orders")
    public Result<Long> buy(@PathVariable long id, @RequestHeader("X-User-Id") long userId) {
        return Result.ok(orderService.buy(id, userId));
    }
}
