package com.zengbohan.aurora.order.controller;

import com.zengbohan.aurora.common.result.Result;
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
}
