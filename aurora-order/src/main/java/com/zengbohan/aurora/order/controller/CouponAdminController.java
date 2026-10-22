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

@RestController
@RequestMapping("/admin/coupons")
@RequireAdmin
public class CouponAdminController {

    private final CouponService couponService;

    public CouponAdminController(CouponService couponService) {
        this.couponService = couponService;
    }

    @PostMapping
    public Result<Long> create(@RequestBody CreateCouponTemplateRequest request) {
        return Result.ok(couponService.createTemplate(new CouponService.CreateTemplate(
                request.title(), request.thresholdAmount(), request.discountAmount(), request.total(),
                request.claimStartAt(), request.claimEndAt(), request.validDays())));
    }

    @GetMapping
    public Result<List<CouponTemplateView>> list() {
        return Result.ok(couponService.listTemplates());
    }
}
