package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.order.entity.Order;
import com.zengbohan.aurora.order.mapper.OrderMapper;
import com.zengbohan.aurora.order.service.OrderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Scan fallback for the delayed message: closes overdue CREATED orders. */
@Component
public class CloseTimeoutJob {

    private final OrderMapper orderMapper;
    private final OrderService orderService;

    public CloseTimeoutJob(OrderMapper orderMapper, OrderService orderService) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${aurora.order.close-scan-interval-ms:60000}", initialDelay = 75_000)
    public void closeOverdue() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(orderService.closeTimeoutSeconds());
        for (Order overdue : orderMapper.findTimedOut(deadline)) {
            orderService.closeIfPending(overdue.getId());
        }
    }
}
