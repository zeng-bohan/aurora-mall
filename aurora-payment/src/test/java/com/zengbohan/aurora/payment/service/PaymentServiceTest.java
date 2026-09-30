package com.zengbohan.aurora.payment.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.api.order.OrderSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengbohan.aurora.payment.client.OrderClient;
import com.zengbohan.aurora.payment.entity.PaymentOrder;
import com.zengbohan.aurora.payment.mapper.PaymentOrderMapper;
import com.zengbohan.aurora.payment.mq.PaymentEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private PaymentOrderMapper mapper;
    private OrderClient orderClient;
    private PaymentEventPublisher publisher;
    private PaymentService service;

    private static final OrderSummary CREATED_ORDER =
            new OrderSummary(1001L, 7L, 1L, 2, new BigDecimal("39.80"), 0);

    @BeforeEach
    void setUp() {
        mapper = mock(PaymentOrderMapper.class);
        orderClient = mock(OrderClient.class);
        publisher = mock(PaymentEventPublisher.class);
        service = new PaymentService(mapper, orderClient, publisher, new ObjectMapper());
        when(orderClient.byId(1001L)).thenReturn(Result.ok(CREATED_ORDER));
    }

    @Test
    void lateCallbackForClosedOrderAutoRefundsAndSkipsEvent() {
        // 关单赢了竞态（status=2 CLOSED）后回调才到：mock 通道语义=自动退款
        when(mapper.findByOrderId(1001L)).thenReturn(payment(0)).thenReturn(payment(2));
        when(orderClient.byId(1001L)).thenReturn(Result.ok(
                new OrderSummary(1001L, 7L, 1L, 2, new BigDecimal("39.80"), 2)));

        PaymentOrder result = service.handleMockCallback(1001L);

        org.mockito.Mockito.verify(mapper).markRefunded(1001L);
        org.mockito.Mockito.verify(publisher, org.mockito.Mockito.never())
                .sendOrderPaid(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString());
        org.assertj.core.api.Assertions.assertThat(result.getStatus())
                .isEqualTo(PaymentOrder.STATUS_REFUNDED);
    }

    @Test
    void byOrderIdRejectsOtherUsersUniformlyAsNotFound() {
        // CREATED_ORDER 归属 user 1（见常量定义）
        when(mapper.findByOrderId(1001L)).thenReturn(payment(0));

        assertThatThrownBy(() -> service.byOrderId(999L, 1001L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void byOrderIdReturnsPaymentForOwner() {
        when(mapper.findByOrderId(1001L)).thenReturn(payment(0));

        assertThat(service.byOrderId(7L, 1001L).getStatus()).isZero();
    }

    private PaymentOrder payment(int status) {
        PaymentOrder p = new PaymentOrder();
        p.setOrderId(1001L);
        p.setAmount(new BigDecimal("39.80"));
        p.setStatus(status);
        return p;
    }

    @Test
    void initiateCreatesPaymentOrderWithOrderAmount() {
        when(mapper.findByOrderId(1001L)).thenReturn(null);

        PaymentOrder created = service.initiate(7L, 1001L);

        ArgumentCaptor<PaymentOrder> captor = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("39.80");
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentOrder.STATUS_PAYING);
        assertThat(created).isNotNull();
    }

    @Test
    void initiateReturnsExistingPaymentOrderForReplay() {
        PaymentOrder existing = payment(PaymentOrder.STATUS_PAYING);
        when(mapper.findByOrderId(1001L)).thenReturn(existing);

        PaymentOrder result = service.initiate(7L, 1001L);

        assertThat(result).isSameAs(existing);
        verify(mapper, never()).insert(any(PaymentOrder.class));
    }

    @Test
    void initiateLosingTheUniqueIndexRaceReturnsTheWinner() {
        when(mapper.findByOrderId(1001L)).thenReturn(null).thenReturn(payment(PaymentOrder.STATUS_PAYING));
        when(mapper.insert(any(PaymentOrder.class)))
                .thenThrow(new DuplicateKeyException("uk_payment_order"));

        PaymentOrder result = service.initiate(7L, 1001L);

        assertThat(result.getStatus()).isEqualTo(PaymentOrder.STATUS_PAYING);
    }

    @Test
    void initiateRejectsNonCreatedOrder() {
        when(mapper.findByOrderId(1001L)).thenReturn(null);
        when(orderClient.byId(1001L)).thenReturn(Result.ok(
                new OrderSummary(1001L, 7L, 1L, 2, new BigDecimal("39.80"), 2)));

        assertThatThrownBy(() -> service.initiate(7L, 1001L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    void initiateUnknownOrderIsNotFound() {
        when(mapper.findByOrderId(1001L)).thenReturn(null);
        when(orderClient.byId(1001L)).thenReturn(Result.fail(ErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> service.initiate(7L, 1001L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void initiateForSomeoneElsesOrderIsNotFound() {
        when(mapper.findByOrderId(1001L)).thenReturn(null);
        when(orderClient.byId(1001L)).thenReturn(Result.ok(
                new OrderSummary(1001L, 999L, 1L, 2, new BigDecimal("39.80"), 0)));

        assertThatThrownBy(() -> service.initiate(7L, 1001L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void firstCallbackFlipsToPaidAndPublishes() {
        when(mapper.findByOrderId(1001L)).thenReturn(payment(PaymentOrder.STATUS_PAYING));
        when(mapper.markPaid(1001L)).thenReturn(1);
        when(mapper.findByOrderId(1001L)).thenReturn(payment(PaymentOrder.STATUS_PAID));

        PaymentOrder result = service.handleMockCallback(1001L);

        assertThat(result.getStatus()).isEqualTo(PaymentOrder.STATUS_PAID);
        verify(publisher).sendOrderPaid(eq("1001"), anyString());
    }

    @Test
    void duplicateCallbackRepublishesWithoutStateChange() {
        when(mapper.findByOrderId(1001L)).thenReturn(payment(PaymentOrder.STATUS_PAID));
        when(mapper.markPaid(1001L)).thenReturn(0);

        PaymentOrder result = service.handleMockCallback(1001L);

        assertThat(result.getStatus()).isEqualTo(PaymentOrder.STATUS_PAID);
        // replay is the recovery channel for a lost publish
        verify(publisher).sendOrderPaid(eq("1001"), anyString());
    }

    @Test
    void callbackForUnknownOrderIsNotFound() {
        when(mapper.findByOrderId(1001L)).thenReturn(null);

        assertThatThrownBy(() -> service.handleMockCallback(1001L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.NOT_FOUND.getCode());
        verify(publisher, never()).sendOrderPaid(anyString(), anyString());
    }
}
