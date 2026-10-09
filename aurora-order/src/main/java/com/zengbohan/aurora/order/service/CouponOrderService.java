package com.zengbohan.aurora.order.service;

import com.zengbohan.aurora.order.dto.PlaceOrderRequest;
import org.springframework.stereotype.Service;

/**
 * 带券下单的编排（M5 S5）：券域（{@link CouponService}）与订单域（{@link OrderService}）
 * 之间的一层薄胶水。
 * <p>
 * 为什么单独成类而不是把券塞进 OrderService：券的校验/锁定是券域的职责，订单域只接受一个
 * {@link OrderService.CouponHook} 回调——两边各自演进，互不认识对方的表。
 * <p>
 * 这里**刻意不开事务**：券的锁定已经落在订单自己的本地事务里（{@code CouponUseHook.bind}），
 * 再套一层事务只会扩大锁的范围，却不增加任何保证。
 */
@Service
public class CouponOrderService {

    private final CouponService couponService;
    private final OrderService orderService;

    public CouponOrderService(CouponService couponService, OrderService orderService) {
        this.couponService = couponService;
        this.orderService = orderService;
    }

    /**
     * 用一张券下单：券校验（归属/状态/有效期）→ 订单事务内锁定并抵扣 → 返回订单号。
     * 失败语义：门槛不足、券不可用、券被并发用掉都会在订单落库前抛出，预扣由订单侧归还。
     */
    public long placeWithCoupon(long userId, PlaceOrderRequest request, String idempotencyKey, long couponId) {
        CouponService.CouponUseHook hook = couponService.prepareUse(couponId, userId);
        return orderService.placeOrder(userId, request, idempotencyKey, hook);
    }
}
