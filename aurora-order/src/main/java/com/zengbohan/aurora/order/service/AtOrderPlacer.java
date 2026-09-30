package com.zengbohan.aurora.order.service;

import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.id.SegmentIdGenerator;
import com.zengbohan.aurora.order.client.InventoryClient;
import com.zengbohan.aurora.order.client.ProductClient;
import com.zengbohan.aurora.order.dto.PlaceOrderRequest;
import com.zengbohan.aurora.order.entity.Order;
import com.zengbohan.aurora.order.mapper.OrderMapper;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Seata AT comparison scenario (aurora.tx.mode=at): one global transaction
 * spans the inventory DB reservation and the order row. No redis, no MQ -
 * the contrast against the main path is exactly the point: both side effects
 * commit locally and undo_log drives the rollback when anything fails.
 * Close-timeout for AT orders is handled by the scan job (releaseDb branch).
 */
@Service
public class AtOrderPlacer {

    private final SegmentIdGenerator idGenerator;
    private final ProductGuard productGuard;
    private final InventoryClient inventoryClient;
    private final OrderMapper orderMapper;

    public AtOrderPlacer(SegmentIdGenerator idGenerator, ProductGuard productGuard,
                         InventoryClient inventoryClient, OrderMapper orderMapper) {
        this.idGenerator = idGenerator;
        this.productGuard = productGuard;
        this.inventoryClient = inventoryClient;
        this.orderMapper = orderMapper;
    }

    @GlobalTransactional(name = "aurora-place-order-at", rollbackFor = Exception.class)
    public long placeAt(long userId, PlaceOrderRequest request) {
        ProductSnapshot product =
                productGuard.load(request.skuId());
        BigDecimal total = product.price().multiply(BigDecimal.valueOf(request.quantity()));

        // branch 1: order row - committed locally, undone by seata on failure
        long orderId = idGenerator.nextId();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setSkuId(request.skuId());
        order.setQuantity(request.quantity());
        order.setTotalAmount(total);
        order.setStatus(Order.STATUS_CREATED);
        order.setTxMode("at");
        orderMapper.insert(order);

        // branch 2: inventory db reservation (undo_log protected). A failure
        // here rolls the committed order row back through branch 1's undo_log
        // - the exact behaviour this comparison scenario exists to show.
        Result<Void> reserve;
        try {
            reserve = inventoryClient.reserveDb(request.skuId(), new InventoryClient.StockRequest(request.quantity()));
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "库存服务不可用");
        }
        if (reserve != null && reserve.code() == ErrorCode.INVENTORY_INSUFFICIENT.getCode()) {
            throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT);
        }
        if (reserve == null || reserve.code() != ErrorCode.SUCCESS.getCode()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "库存服务不可用");
        }
        return orderId;
    }
}
