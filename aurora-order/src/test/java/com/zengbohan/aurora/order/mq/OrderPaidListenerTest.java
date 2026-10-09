package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.order.mq.OrderPaidListener.OrderPaidEvent;
import com.zengbohan.aurora.order.service.CouponService;
import com.zengbohan.aurora.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderPaidListenerTest {

    private OrderService orderService;
    private OrderEventPublisher publisher;
    private CouponService couponService;
    private OrderPaidListener listener;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        publisher = mock(OrderEventPublisher.class);
        couponService = mock(CouponService.class);
        listener = new OrderPaidListener(orderService, publisher, couponService);
    }

    private OrderPaidEvent event() {
        return new OrderPaidEvent("msg-1", 1001L, 7L, 1);
    }

    @Test
    void appliedPaidOrderPublishesNothing() {
        when(orderService.markPaid(1001L)).thenReturn(true);

        listener.onMessage(event());

        verify(publisher, never()).publishRefundRequest(anyLong());
    }

    @Test
    void latePaymentForClosedOrderPublishesRefundSignal() {
        when(orderService.markPaid(1001L)).thenReturn(false);
        when(orderService.isClosed(1001L)).thenReturn(true);

        listener.onMessage(event());

        verify(publisher).publishRefundRequest(1001L);
    }

    @Test
    void failedRefundSignalPublishesPropagateForRedelivery() {
        // 退款信号是闭环的最后一步，失败必须让 order-paid 重投，不能静默丢弃
        when(orderService.markPaid(1001L)).thenReturn(false);
        when(orderService.isClosed(1001L)).thenReturn(true);
        doThrow(new IllegalStateException("rocketmq down")).when(publisher).publishRefundRequest(1001L);

        assertThatThrownBy(() -> listener.onMessage(event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rocketmq down");
    }

    @Test
    void alreadyPaidReplayIsIgnored() {
        when(orderService.markPaid(1001L)).thenReturn(false);
        when(orderService.isClosed(1001L)).thenReturn(false);

        listener.onMessage(event());

        verify(publisher, never()).publishRefundRequest(anyLong());
    }
}
