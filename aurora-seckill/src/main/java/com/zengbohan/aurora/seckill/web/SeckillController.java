package com.zengbohan.aurora.seckill.web;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.seckill.service.SeckillActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端：浏览秒杀活动（未结束的）与单个详情（含剩余库存与阶段）。
 * 只读无副作用，不需要用户身份。
 */
@RestController
@RequestMapping("/activities")
public class SeckillController {

    private final SeckillActivityService activityService;

    public SeckillController(SeckillActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping
    public Result<List<SeckillActivityService.SeckillActivityView>> list() {
        return Result.ok(activityService.listNotEndedViews());
    }

    @GetMapping("/{id}")
    public Result<SeckillActivityService.SeckillActivityView> detail(@PathVariable long id) {
        return Result.ok(activityService.viewOf(id));
    }
}
