package com.zengbohan.aurora.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.id.SegmentIdGenerator;
import com.zengbohan.aurora.order.client.InventoryClient;
import com.zengbohan.aurora.order.dto.PlaceOrderRequest;
import com.zengbohan.aurora.order.entity.Order;
import com.zengbohan.aurora.order.mapper.OrderMapper;
import com.zengbohan.aurora.order.mapper.TxMessageMapper;
import com.zengbohan.aurora.order.mq.OrderEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 带券下单：抵扣必须体现在**订单金额**上、券的锁定必须发生在订单事务内、
 * 门槛不满足时连预扣都不做。这三条是 S5 的核心承诺，单靠券域测试证明不了。
 */
class OrderCouponPlacementTest {

    private static final long ORDER_ID = 990001L;
    private static final long USER_ID = 7L;
    private static final long SKU_ID = 1L;
    private static final String KEY = "key-1";

    private InventoryClient inventoryClient;
    private OrderMapper orderMapper;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        ProductGuard productGuard = mock(ProductGuard.class);
        inventoryClient = mock(InventoryClient.class);
        orderMapper = mock(OrderMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        SegmentIdGenerator idGenerator = mock(SegmentIdGenerator.class);

        when(idGenerator.nextId()).thenReturn(ORDER_ID);
        when(productGuard.load(SKU_ID)).thenReturn(new ProductSnapshot(
                SKU_ID, "测试商品", new BigDecimal("100.00"), 100, ProductSnapshot.STATUS_ON_SALE));
        when(inventoryClient.reserve(anyLong(), any())).thenReturn(Result.ok());
        // 事务模板：直接执行回调（真库的事务语义由集成测试覆盖，这里只验证顺序与取值）
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        orderService = new OrderService(new ObjectMapper(), idGenerator, productGuard, inventoryClient,
                orderMapper, mock(TxMessageMapper.class), transactionTemplate,
                mock(OrderEventPublisher.class), mock(AtOrderPlacer.class), 16, 1800L, "mq", false);
    }

    @Test
    void discountLandsInTheOrderAmountAndCouponIsBoundInsideTheTransaction() {
        OrderService.CouponHook hook = mock(OrderService.CouponHook.class);
        when(hook.discountFor(new BigDecimal("100.00"))).thenReturn(new BigDecimal("20.00"));

        long orderId = orderService.placeOrder(USER_ID, request(), KEY, hook);

        assertThat(orderId).isEqualTo(ORDER_ID);
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(saved.capture());
        assertThat(saved.getValue().getTotalAmount())
                .as("订单金额 = 商品价 x 数量 - 券抵扣")
                .isEqualByComparingTo("80.00");
        verify(hook).bind(ORDER_ID);
    }

    @Test
    void thresholdRejectionHappensBeforeStockReserveAndOrderInsert() {
        OrderService.CouponHook hook = mock(OrderService.CouponHook.class);
        when(hook.discountFor(any())).thenThrow(
                new BusinessException(ErrorCode.COUPON_THRESHOLD_NOT_MET));

        assertThatThrownBy(() -> orderService.placeOrder(USER_ID, request(), KEY, hook))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_THRESHOLD_NOT_MET);

        // 券不可用的判断在预扣之前：不该白扣一次库存，也不该落订单
        verifyNoInteractions(inventoryClient);
        verify(orderMapper, never()).insert(any(Order.class));
        verify(hook, never()).bind(anyLong());
    }

    @Test
    void bindFailureRollsBackOrderAndReturnsTheReservedStock() {
        OrderService.CouponHook hook = mock(OrderService.CouponHook.class);
        when(hook.discountFor(any())).thenReturn(new BigDecimal("20.00"));
        doThrow(new BusinessException(ErrorCode.COUPON_NOT_USABLE)).when(hook).bind(ORDER_ID);

        assertThatThrownBy(() -> orderService.placeOrder(USER_ID, request(), KEY, hook))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COUPON_NOT_USABLE);

        // 本地事务失败 → 预扣必须归还（走的是既有的 rollbackStockQuietly 路径）
        verify(inventoryClient).rollback(anyLong(), any());
    }

    private static PlaceOrderRequest request() {
        return new PlaceOrderRequest(SKU_ID, 1);
    }
}
