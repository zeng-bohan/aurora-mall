package com.zengbohan.aurora.order.client;

import com.zengbohan.aurora.api.order.PaymentSummary;
import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;

@FeignClient(name = "aurora-payment")
public interface PaymentClient {

    record RefundRequest(long refundId, BigDecimal amount) {
    }

    @PostMapping("/internal/payments/trades/{tradeId}/refunds")
    Result<PaymentSummary> refund(@PathVariable("tradeId") long tradeId,
                                  @RequestBody RefundRequest request);

    @PostMapping("/internal/payments/trades/{tradeId}/late-refund")
    Result<PaymentSummary> lateRefund(@PathVariable("tradeId") long tradeId);
}
