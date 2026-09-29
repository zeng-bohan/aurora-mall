package com.zengbohan.aurora.order.controller;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.order.entity.Order;
import com.zengbohan.aurora.order.mapper.OrderMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Service-to-service order lookup (payment initiation). Reached only with
 * the internal secret - no user context on this path by design.
 */
@RestController
public class OrderInternalController {

    public record OrderInternalView(long orderId, long userId, long skuId, int quantity,
                                    BigDecimal totalAmount, int status) {
    }

    private final OrderMapper orderMapper;

    public OrderInternalController(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @GetMapping("/internal/orders/{id}")
    public Result<OrderInternalView> byId(@PathVariable long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return Result.ok(new OrderInternalView(order.getId(), order.getUserId(), order.getSkuId(),
                order.getQuantity(), order.getTotalAmount(), order.getStatus()));
    }
}
