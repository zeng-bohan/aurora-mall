package com.zengbohan.aurora.order.controller;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.order.dto.CouponTemplateView;
import com.zengbohan.aurora.order.dto.CouponView;
import com.zengbohan.aurora.order.service.CouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户端：看可领的券、领券、看自己的券。身份来自网关注入的 X-User-Id。 */
@RestController
@RequestMapping("/coupons")
public class CouponController {

    private final CouponService couponService;

    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping("/templates")
    public Result<List<CouponTemplateView>> templates() {
        return Result.ok(couponService.claimableTemplates());
    }

    @PostMapping("/{templateId}/claim")
    public Result<Long> claim(@RequestHeader("X-User-Id") long userId,
                              @PathVariable long templateId) {
        return Result.ok(couponService.claim(templateId, userId));
    }

    @GetMapping("/mine")
    public Result<List<CouponView>> mine(@RequestHeader("X-User-Id") long userId) {
        return Result.ok(couponService.myCoupons(userId));
    }
}
