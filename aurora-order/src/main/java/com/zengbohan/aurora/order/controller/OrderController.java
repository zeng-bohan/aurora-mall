package com.zengbohan.aurora.order.controller;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.order.dto.OrderPage;
import com.zengbohan.aurora.order.dto.OrderView;
import com.zengbohan.aurora.order.dto.PlaceOrderRequest;
import com.zengbohan.aurora.order.service.CouponOrderService;
import com.zengbohan.aurora.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final CouponOrderService couponOrderService;

    public OrderController(OrderService orderService, CouponOrderService couponOrderService) {
        this.orderService = orderService;
        this.couponOrderService = couponOrderService;
    }

    @PostMapping
    public Result<Long> place(@RequestHeader("X-User-Id") long userId,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                              @RequestHeader(value = "Coupon-Id", required = false) Long couponId,
                              @Valid @RequestBody PlaceOrderRequest request) {
        if (couponId != null) {
            // 用券下单：与 Idempotency-Key 同样属于请求上下文，不塞进请求体
            return Result.ok(couponOrderService.placeWithCoupon(userId, request, idempotencyKey, couponId));
        }
        return Result.ok(orderService.placeOrder(userId, request, idempotencyKey));
    }

    @GetMapping("/{id}")
    public Result<OrderView> detail(@RequestHeader("X-User-Id") long userId,
                                    @PathVariable long id) {
        return Result.ok(orderService.getOrder(userId, id));
    }

    // 我的订单列表：登录用户只能看到自己的（user_id 写进 SQL 条件，不靠调用方自觉）。
    // 前端原本只能把下过的订单号存在浏览器本地，就是因为缺这个端点。
    @GetMapping
    public Result<OrderPage> list(@RequestHeader("X-User-Id") long userId,
                                  @RequestParam(defaultValue = "1") long current,
                                  @RequestParam(defaultValue = "10") long size) {
        return Result.ok(orderService.listOrders(userId, current, size));
    }
}
