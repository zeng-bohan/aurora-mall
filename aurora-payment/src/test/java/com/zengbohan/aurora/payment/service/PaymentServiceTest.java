package com.zengbohan.aurora.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengbohan.aurora.api.order.PaymentSummary;
import com.zengbohan.aurora.api.order.TradeSummary;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.payment.channel.ChannelSignatureVerifier;
import com.zengbohan.aurora.payment.client.OrderClient;
import com.zengbohan.aurora.payment.entity.PaymentOrder;
import com.zengbohan.aurora.payment.entity.PaymentRefund;
import com.zengbohan.aurora.payment.mapper.PaymentOrderMapper;
import com.zengbohan.aurora.payment.mapper.PaymentRefundMapper;
import com.zengbohan.aurora.payment.mq.PaymentEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private PaymentOrderMapper paymentMapper;
    private PaymentRefundMapper refundMapper;
    private OrderClient orderClient;
    private PaymentEventPublisher publisher;
    private ChannelSignatureVerifier verifier;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        paymentMapper = mock(PaymentOrderMapper.class);
        refundMapper = mock(PaymentRefundMapper.class);
        orderClient = mock(OrderClient.class);
        publisher = mock(PaymentEventPublisher.class);
        verifier = new ChannelSignatureVerifier("test-channel-secret-0123456789");
        service = new PaymentService(paymentMapper, refundMapper, orderClient, publisher,
                verifier, new ObjectMapper());
    }

    @Test
    void callbackWithBadSignatureIsRejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.handleMockCallback(11L, new BigDecimal("100.00"), "bad"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);

        verify(paymentMapper, never()).markPaid(anyLong(), any());
        verify(publisher, never()).sendPaid(anyString(), anyString());
    }

    @Test
    void callbackMarksPaidAndPublishesOnlyForOwnedAmount() {
        PaymentOrder paying = payment(11L, "PAYING", "100.00");
        PaymentOrder paid = payment(11L, "PAID", "100.00");
        when(paymentMapper.findByTradeId(11L)).thenReturn(paying, paid, paid);
        when(paymentMapper.markPaid(anyLong(), any(LocalDateTime.class))).thenReturn(1);
        when(orderClient.byId(11L)).thenReturn(Result.ok(trade(11L, TradeSummary.STATUS_CREATED)));
        when(paymentMapper.markEventPublished(11L)).thenReturn(1);

        PaymentOrder result = service.handleMockCallback(11L, new BigDecimal("100.00"),
                verifier.sign(11L, "100.00"));

        assertThat(result.getStatus()).isEqualTo("PAID");
        verify(paymentMapper).markPaid(anyLong(), any(LocalDateTime.class));
        verify(publisher).sendPaid(anyString(), anyString());
    }

    @Test
    void callbackForClosedTradeMarksRefundedAndNeverPublishes() {
        PaymentOrder paying = payment(12L, "PAYING", "100.00");
        PaymentOrder refunded = payment(12L, "REFUNDED", "100.00");
        when(paymentMapper.findByTradeId(12L)).thenReturn(paying, refunded);
        when(paymentMapper.markRefundedLate(12L)).thenReturn(1);
        when(orderClient.byId(12L)).thenReturn(Result.ok(trade(12L, TradeSummary.STATUS_CLOSED)));

        PaymentOrder result = service.handleMockCallback(12L, new BigDecimal("100.00"),
                verifier.sign(12L, "100.00"));

        assertThat(result.getStatus()).isEqualTo("REFUNDED");
        verify(paymentMapper).markRefundedLate(12L);
        verify(publisher, never()).sendPaid(anyString(), anyString());
    }

    @Test
    void closeWinningTheRaceAfterMarkPaidAutoRefunds() {
        PaymentOrder paying = payment(13L, "PAYING", "100.00");
        PaymentOrder paid = payment(13L, "PAID", "100.00");
        PaymentOrder refunded = payment(13L, "REFUNDED", "100.00");
        when(paymentMapper.findByTradeId(13L)).thenReturn(paying, paid, refunded);
        when(paymentMapper.markPaid(anyLong(), any(LocalDateTime.class))).thenReturn(1);
        when(paymentMapper.markRefundedLate(13L)).thenReturn(1);
        when(orderClient.byId(13L)).thenReturn(
                Result.ok(trade(13L, TradeSummary.STATUS_CREATED)),
                Result.ok(trade(13L, TradeSummary.STATUS_CLOSED)));

        PaymentOrder result = service.handleMockCallback(13L, new BigDecimal("100.00"),
                verifier.sign(13L, "100.00"));

        assertThat(result.getStatus()).isEqualTo("REFUNDED");
        verify(paymentMapper).markRefundedLate(13L);
        verify(publisher, never()).sendPaid(anyString(), anyString());
    }

    @Test
    void callbackAmountMismatchIsRejected() {
        PaymentOrder paying = payment(14L, "PAYING", "100.00");
        when(paymentMapper.findByTradeId(14L)).thenReturn(paying);

        assertThatThrownBy(() -> service.handleMockCallback(14L, new BigDecimal("99.00"),
                verifier.sign(14L, "99.00")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PARAM_ERROR);
        verify(paymentMapper, never()).markPaid(anyLong(), any());
    }

    @Test
    void duplicateRefundRequestIsIdempotentByRefundId() {
        PaymentOrder paid = payment(15L, "PAID", "200.00");
        paid.setRefundedAmount(new BigDecimal("80.00"));
        when(paymentMapper.findByTradeId(15L)).thenReturn(paid);
        PaymentRefund existing = new PaymentRefund();
        existing.setRefundId(901L);
        existing.setTradeId(15L);
        existing.setAmount(new BigDecimal("80.00"));
        when(refundMapper.selectById(901L)).thenReturn(existing);

        PaymentSummary summary = service.internalRefund(15L, 901L, new BigDecimal("80.00"));

        assertThat(summary.refundedAmount()).isEqualByComparingTo("80.00");
        verify(refundMapper, never()).insert(any(PaymentRefund.class));
        verify(paymentMapper, never()).applyRefund(anyLong(), any());
    }

    @Test
    void lateRefundIsGuardedAndIdempotent() {
        PaymentOrder refunded = payment(16L, "REFUNDED", "100.00");
        when(paymentMapper.findByTradeId(16L)).thenReturn(refunded);
        when(paymentMapper.markRefundedLate(16L)).thenReturn(0);

        PaymentSummary summary = service.lateRefund(16L);

        assertThat(summary.status()).isEqualTo("REFUNDED");
        verify(paymentMapper).markRefundedLate(16L);
    }

    private static PaymentOrder payment(long tradeId, String status, String amount) {
        PaymentOrder payment = new PaymentOrder();
        payment.setId(tradeId + 100);
        payment.setTradeId(tradeId);
        payment.setTradeNo("A" + tradeId);
        payment.setUserId(7L);
        payment.setAmount(new BigDecimal(amount));
        payment.setRefundedAmount(BigDecimal.ZERO);
        payment.setStatus(status);
        return payment;
    }

    private static TradeSummary trade(long tradeId, String status) {
        return new TradeSummary(tradeId, "A" + tradeId, 7L, "MALL", status,
                new BigDecimal("100.00"), BigDecimal.ZERO, java.util.List.of());
    }
}
