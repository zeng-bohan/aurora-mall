package com.zengbohan.aurora.order.mq;

import com.zengbohan.aurora.order.entity.Order;
import com.zengbohan.aurora.order.mapper.OrderMapper;
import com.zengbohan.aurora.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

// 延迟消息的扫描兜底：关闭已超期的 CREATED 订单。
@Component
public class CloseTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(CloseTimeoutJob.class);

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
            try {
                orderService.closeIfPending(overdue.getId());
            } catch (RuntimeException e) {
                // 毒丸隔离：一条失败不能饿死本轮后续记录（下轮还会重扫到它）
                log.error("timed-out close failed for order {}", overdue.getId(), e);
            }
        }
        // 补偿扫描：此前库存释放失败的已关闭订单
        for (Order stranded : orderMapper.findUnreleasedClosed()) {
            try {
                orderService.closeIfPending(stranded.getId());
            } catch (RuntimeException e) {
                log.error("compensating release failed for order {}", stranded.getId(), e);
            }
        }
    }
}
