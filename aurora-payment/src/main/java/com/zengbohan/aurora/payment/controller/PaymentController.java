package com.zengbohan.aurora.payment.controller;

import com.zengbohan.aurora.api.order.PaymentSummary;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.payment.channel.ChannelSignatureVerifier;
import com.zengbohan.aurora.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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

    public record InitiateRequest(@NotNull @Min(1) Long tradeId) {
    }

    public record PayRequest(@NotNull @Min(1) Long tradeId,
                             @NotNull @DecimalMin("0.01") BigDecimal amount) {
    }

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public Result<PaymentSummary> initiate(@RequestHeader("X-User-Id") long userId,
                                           @Valid @RequestBody InitiateRequest request) {
        return Result.ok(PaymentServiceSummary.of(paymentService.initiate(userId, request.tradeId())));
    }

    @PostMapping("/mock-callback")
    public Result<PaymentSummary> mockCallback(
            @RequestHeader(value = ChannelSignatureVerifier.HEADER, required = false) String signature,
            @Valid @RequestBody PayRequest request) {
        return Result.ok(PaymentServiceSummary.of(
                paymentService.handleMockCallback(request.tradeId(), request.amount(), signature)));
    }

    @GetMapping("/{tradeId}")
    public Result<PaymentSummary> byTrade(@RequestHeader("X-User-Id") long userId,
                                          @PathVariable long tradeId) {
        return Result.ok(PaymentServiceSummary.of(paymentService.byTradeId(userId, tradeId)));
    }

    /**
     * 模拟支付入口：服务端签名后复用 mock 回调，浏览器不接触渠道密钥。
     */
    @PostMapping("/{tradeId}/mock-pay")
    public Result<PaymentSummary> mockPay(@RequestHeader("X-User-Id") long userId,
                                          @PathVariable long tradeId) {
        return Result.ok(PaymentServiceSummary.of(paymentService.mockPay(userId, tradeId)));
    }

    private static final class PaymentServiceSummary {
        static PaymentSummary of(com.zengbohan.aurora.payment.entity.PaymentOrder payment) {
            return new PaymentSummary(payment.getId(), payment.getTradeId(), payment.getTradeNo(),
                    payment.getAmount(), payment.getRefundedAmount(), payment.getStatus());
        }
    }
}
