package com.zengbohan.aurora.payment.controller;

import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.payment.entity.PaymentOrder;
import com.zengbohan.aurora.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    public record PayRequest(@NotNull @Min(1) Long orderId) {
    }

    public record PaymentView(long paymentId, long orderId, BigDecimal amount, int status) {
    }

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public Result<PaymentView> initiate(@RequestHeader("X-User-Id") long userId,
                                        @Valid @RequestBody PayRequest request) {
        return Result.ok(toView(paymentService.initiate(userId, request.orderId())));
    }

    /** The simulated third-party async callback (would be signed in production). */
    @PostMapping("/mock-callback")
    public Result<PaymentView> mockCallback(@Valid @RequestBody PayRequest request) {
        return Result.ok(toView(paymentService.handleMockCallback(request.orderId())));
    }

    @GetMapping("/{orderId}")
    public Result<PaymentView> byOrder(@RequestHeader("X-User-Id") long userId,
                                       @PathVariable long orderId) {
        return Result.ok(toView(paymentService.byOrderId(userId, orderId)));
    }

    private PaymentView toView(PaymentOrder payment) {
        return new PaymentView(payment.getId(), payment.getOrderId(),
                payment.getAmount(), payment.getStatus());
    }
}
