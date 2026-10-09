package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.order.service.CouponService;
import com.zengbohan.aurora.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OrderCloseListenerTest {

    private OrderService orderService;
    private CouponService couponService;
    private OrderCloseListener listener;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        couponService = mock(CouponService.class);
        listener = new OrderCloseListener(orderService, couponService);
    }

    @Test
    void validPayloadTriggersClose() {
        listener.onMessage("1001");
        verify(orderService).closeIfPending(1001L);
    }

    @Test
    void malformedPayloadIsDroppedInsteadOfRedelivered() {
        // 坏 payload 抛 NumberFormatException 会导致无限重投进 DLQ：必须记录后丢弃
        listener.onMessage("not-a-number");
        listener.onMessage("");
        verify(orderService, never()).closeIfPending(anyLong());
    }
}
