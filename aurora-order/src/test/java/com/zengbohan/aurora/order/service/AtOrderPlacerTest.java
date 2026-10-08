package com.zengbohan.aurora.order.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.id.SegmentIdGenerator;
import com.zengbohan.aurora.order.client.InventoryClient;
import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.order.client.ProductClient;
import com.zengbohan.aurora.order.dto.PlaceOrderRequest;
import com.zengbohan.aurora.order.entity.Order;
import com.zengbohan.aurora.order.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AtOrderPlacerTest {

    private SegmentIdGenerator idGenerator;
    private ProductClient productClient;
    private InventoryClient inventoryClient;
    private OrderMapper orderMapper;
    private AtOrderPlacer placer;

    private static final ProductSnapshot ON_SALE =
            new ProductSnapshot(1L, "Aurora Mug", new BigDecimal("19.90"), 100, 1);

    @BeforeEach
    void setUp() {
        idGenerator = mock(SegmentIdGenerator.class);
        productClient = mock(ProductClient.class);
        inventoryClient = mock(InventoryClient.class);
        orderMapper = mock(OrderMapper.class);
        placer = new AtOrderPlacer(idGenerator, new ProductGuard(productClient),
                inventoryClient, orderMapper);
        when(idGenerator.nextId()).thenReturn(5001L);
        when(productClient.detail(1L)).thenReturn(Result.ok(ON_SALE));
        when(inventoryClient.reserveDb(eq(1L), any())).thenReturn(Result.ok());
    }

    @Test
    void placesOrderWithDbReservationAndAtModeMarker() {
        long orderId = placer.placeAt(7L, new PlaceOrderRequest(1L, 2));

        assertThat(orderId).isEqualTo(5001L);
        verify(inventoryClient).reserveDb(eq(1L), any());
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(captor.capture());
        assertThat(captor.getValue().getTxMode()).isEqualTo("at");
        assertThat(captor.getValue().getTotalAmount()).isEqualByComparingTo("39.80");
    }

    @Test
    void insufficientStockThrowsAfterOrderInsertSoSeataRollsBothBack() {
        when(inventoryClient.reserveDb(eq(1L), any()))
                .thenReturn(Result.fail(ErrorCode.INVENTORY_INSUFFICIENT));

        assertThatThrownBy(() -> placer.placeAt(7L, new PlaceOrderRequest(1L, 2)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.INVENTORY_INSUFFICIENT.getCode());
        // 订单行已在本地写入：撤销它正是全局事务（undo_log）
        // 用来做对比的地方
        verify(orderMapper).insert(any(Order.class));
    }

    @Test
    void offShelfProductStopsBeforeReservation() {
        when(productClient.detail(1L)).thenReturn(Result.ok(
                new ProductSnapshot(1L, "x", BigDecimal.ONE, 0, 0)));

        assertThatThrownBy(() -> placer.placeAt(7L, new PlaceOrderRequest(1L, 1)))
                .isInstanceOf(BusinessException.class);
        verify(inventoryClient, never()).reserveDb(eq(1L), any());
    }
}
