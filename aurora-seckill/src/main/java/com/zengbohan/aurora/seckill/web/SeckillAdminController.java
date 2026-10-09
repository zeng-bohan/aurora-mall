package com.zengbohan.aurora.seckill.web;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.common.web.RequireAdmin;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import com.zengbohan.aurora.seckill.service.SeckillActivityService;
import com.zengbohan.aurora.seckill.service.SeckillPreheatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 运营端：创建/查看活动与触发预热。挂在 /admin/** 上由 common 的角色守卫拦截。
 * <p>
 * 创建体只做透传：字段校验在 {@code SeckillActivityService}（任何调用方都过同一套规则），
 * 缺字段/非法值最终以 PARAM_ERROR 信封返回。
 */
@RestController
@RequestMapping("/admin/activities")
public class SeckillAdminController {

    private final SeckillActivityService activityService;
    private final SeckillPreheatService preheatService;

    public SeckillAdminController(SeckillActivityService activityService,
                                  SeckillPreheatService preheatService) {
        this.activityService = activityService;
        this.preheatService = preheatService;
    }

    public record CreateActivityRequest(String title, long skuId, BigDecimal seckillPrice,
                                        int totalStock, int perUserLimit,
                                        LocalDateTime startAt, LocalDateTime endAt) {
    }

    @RequireAdmin
    @PostMapping
    public Result<Long> create(@RequestBody CreateActivityRequest request) {
        SeckillActivity created = activityService.create(request.title(), request.skuId(),
                request.seckillPrice(), request.totalStock(), request.perUserLimit(),
                request.startAt(), request.endAt());
        return Result.ok(created.getId());
    }

    @RequireAdmin
    @GetMapping
    public Result<List<SeckillActivityService.SeckillActivityView>> list() {
        return Result.ok(activityService.listNotEndedViews());
    }

    @RequireAdmin
    @PostMapping("/{id}/preheat")
    public Result<SeckillPreheatService.PreheatResult> preheat(@PathVariable long id) {
        return Result.ok(preheatService.preheat(id));
    }
}
