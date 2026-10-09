package com.zengbohan.aurora.seckill.web;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.seckill.dto.SeckillBuyView;
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

    // 抢购：先过 Redis 预扣闸门，抢到名额才落单。
    // 默认模式（mq）返回 {status: QUEUED}，客户端用下面的查询接口轮询结果；
    // sync 模式（对照基线）直接返回 {status: PLACED, orderId}。
    @PostMapping("/{id}/orders")
    public Result<SeckillBuyView> buy(@PathVariable long id, @RequestHeader("X-User-Id") long userId) {
        return Result.ok(orderService.buy(id, userId));
    }

    // 抢购结果查询：排队中返回 QUEUED，已落单返回 PLACED + orderId，失败按业务码返回
    @GetMapping("/{id}/orders/mine")
    public Result<SeckillBuyView> myOrder(@PathVariable long id, @RequestHeader("X-User-Id") long userId) {
        return Result.ok(orderService.mine(id, userId));
    }
}
