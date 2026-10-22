package com.zengbohan.aurora.payment.controller;

import com.zengbohan.aurora.api.order.PaymentSummary;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
public class PaymentInternalController {

    public record RefundRequest(@NotNull @Min(1) Long refundId,
                                @NotNull @DecimalMin("0.01") BigDecimal amount) {
    }

    private final PaymentService paymentService;

    public PaymentInternalController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/internal/payments/trades/{tradeId}/refunds")
    public Result<PaymentSummary> refund(@PathVariable long tradeId,
                                         @Valid @RequestBody RefundRequest request) {
        return Result.ok(paymentService.internalRefund(tradeId, request.refundId(), request.amount()));
    }

    @PostMapping("/internal/payments/trades/{tradeId}/late-refund")
    public Result<PaymentSummary> lateRefund(@PathVariable long tradeId) {
        return Result.ok(paymentService.lateRefund(tradeId));
    }
}
