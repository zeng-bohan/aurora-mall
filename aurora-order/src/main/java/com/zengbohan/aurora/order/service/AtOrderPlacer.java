package com.zengbohan.aurora.order.service;

import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.RemoteCall;
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
 * Seata AT 对比场景（aurora.tx.mode=at）：一个全局事务跨越库存 DB 预占与
 * 订单行。不用 redis、不用 MQ——与主链路的对照正是重点：两侧效果各自本地提交，
 * 任一失败时由 undo_log 驱动回滚。AT 订单的关单超时由扫描 job 处理
 *（releaseDb 分支）。
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

        // 分支 1：订单行——本地提交，失败时由 seata 撤销
        long orderId = idGenerator.nextId();
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setSkuId(request.skuId());
        order.setQuantity(request.quantity());
        order.setTotalAmount(total);
        order.setStatus(Order.STATUS_CREATED);
        order.setTxMode(Order.TX_MODE_AT);
        orderMapper.insert(order);

        // 分支 2：库存 DB 预占（受 undo_log 保护）。此处失败会通过分支 1 的
        // undo_log 把已提交的订单行回滚——这正是该对比场景要展示的行为。
        Result<Void> reserve = RemoteCall.invoke("库存",
                "sku " + request.skuId() + " x" + request.quantity() + " (at)",
                () -> inventoryClient.reserveDb(request.skuId(),
                        new InventoryClient.StockRequest(request.quantity())));
        if (reserve.code() == ErrorCode.INVENTORY_INSUFFICIENT.getCode()) {
            throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT);
        }
        RemoteCall.requireSuccess(reserve, "库存");
        return orderId;
    }
}
