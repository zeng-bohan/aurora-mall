package com.zengbohan.aurora.order.controller;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.common.web.RequireAdmin;
import com.zengbohan.aurora.order.dto.CouponTemplateView;
import com.zengbohan.aurora.order.dto.CreateCouponTemplateRequest;
import com.zengbohan.aurora.order.service.CouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营端：建券与查看模板。挂在 /admin/** 上由 common 的角色守卫拦截
 * （本模块的 /admin/** 目前只有券这一组端点）。
 */
@RestController
@RequestMapping("/admin/coupons")
public class CouponAdminController {

    private final CouponService couponService;

    public CouponAdminController(CouponService couponService) {
        this.couponService = couponService;
    }

    @RequireAdmin
    @PostMapping
    public Result<Long> create(@RequestBody CreateCouponTemplateRequest request) {
        return Result.ok(couponService.createTemplate(new CouponService.CreateTemplate(
                request.title(), request.thresholdAmount(), request.discountAmount(), request.total(),
                request.claimStartAt(), request.claimEndAt(), request.validDays())));
    }

    @RequireAdmin
    @GetMapping
    public Result<List<CouponTemplateView>> list() {
        return Result.ok(couponService.listTemplates());
    }
}
