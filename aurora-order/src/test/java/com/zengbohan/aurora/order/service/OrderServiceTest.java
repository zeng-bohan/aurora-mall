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
import com.zengbohan.aurora.order.entity.TxMessage;
import com.zengbohan.aurora.order.mapper.OrderMapper;
import com.zengbohan.aurora.order.mapper.TxMessageMapper;
import com.zengbohan.aurora.order.mq.OrderEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private SegmentIdGenerator idGenerator;
    private ProductClient productClient;
    private ProductGuard productGuard;
    private AtOrderPlacer atOrderPlacer;
    private InventoryClient inventoryClient;
    private OrderMapper orderMapper;
    private TxMessageMapper txMessageMapper;
    private TransactionTemplate transactionTemplate;
    private OrderEventPublisher publisher;
    private OrderService service;

    private static final ProductSnapshot ON_SALE =
            new ProductSnapshot(1L, "Aurora Mug", new BigDecimal("19.90"), 100, 1);

    @BeforeEach
    void setUp() {
        idGenerator = mock(SegmentIdGenerator.class);
        productClient = mock(ProductClient.class);
        productGuard = new ProductGuard(productClient);
        atOrderPlacer = mock(AtOrderPlacer.class);
        inventoryClient = mock(InventoryClient.class);
        orderMapper = mock(OrderMapper.class);
        txMessageMapper = mock(TxMessageMapper.class);
        transactionTemplate = mock(TransactionTemplate.class);
        publisher = mock(OrderEventPublisher.class);

        // the mocked template runs the callback inline like a real one would
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback =
                    invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        when(idGenerator.nextId()).thenReturn(1001L);
        when(productClient.detail(1L)).thenReturn(Result.ok(ON_SALE));
        when(inventoryClient.reserve(eq(1L), any())).thenReturn(Result.ok());
        when(inventoryClient.rollback(eq(1L), any())).thenReturn(Result.ok());

        service = new OrderService(idGenerator, productGuard, inventoryClient,
                orderMapper, txMessageMapper, transactionTemplate, publisher, atOrderPlacer, 3, 1800, "mq", false);
    }

    @Test
    void happyPathPersistsOrderAndTxMessageThenSendsBothEvents() {
        long orderId = service.placeOrder(7L, new PlaceOrderRequest(1L, 2), "req-1");

        assertThat(orderId).isEqualTo(1001L);
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(orderCaptor.capture());
        Order saved = orderCaptor.getValue();
        assertThat(saved.getId()).isEqualTo(1001L);
        assertThat(saved.getStatus()).isEqualTo(Order.STATUS_CREATED);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("39.80");

        ArgumentCaptor<TxMessage> messageCaptor = ArgumentCaptor.forClass(TxMessage.class);
        verify(txMessageMapper).insert(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getBizKey()).isEqualTo("1001");
        assertThat(messageCaptor.getValue().getStatus()).isEqualTo(TxMessage.STATUS_PENDING);

        verify(inventoryClient).reserve(eq(1L), any());
        verify(publisher).sendStockReservedTransactionally(eq("1001"), anyString());
        verify(publisher).sendCloseTimeout(1001L, 3);
    }

    @Test
    void missingIdempotencyKeyIsRejectedBeforeAnySideEffect() {
        assertThatThrownBy(() -> service.placeOrder(7L, new PlaceOrderRequest(1L, 1), " "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.PARAM_ERROR.getCode());
        verify(inventoryClient, never()).reserve(anyLong(), any());
    }

    @Test
    void localTransactionFailureCompensatesTheStockReservation() {
        doAnswer(invocation -> {
            throw new IllegalStateException("db down");
        }).when(transactionTemplate).executeWithoutResult(any());

        assertThatThrownBy(() -> service.placeOrder(7L, new PlaceOrderRequest(1L, 2), "req-2"))
                .isInstanceOf(IllegalStateException.class);

        verify(inventoryClient).rollback(eq(1L), any());
        verify(publisher, never()).sendStockReservedTransactionally(anyString(), anyString());
    }

    @Test
    void insufficientStockStopsBeforeOrderCreation() {
        when(inventoryClient.reserve(eq(1L), any()))
                .thenReturn(Result.fail(ErrorCode.INVENTORY_INSUFFICIENT));

        assertThatThrownBy(() -> service.placeOrder(7L, new PlaceOrderRequest(1L, 2), "req-3"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.INVENTORY_INSUFFICIENT.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    void offShelfProductIsRejectedBeforeReservation() {
        when(productClient.detail(1L)).thenReturn(Result.ok(
                new ProductSnapshot(1L, "x", BigDecimal.ONE, 0, 0)));

        assertThatThrownBy(() -> service.placeOrder(7L, new PlaceOrderRequest(1L, 2), "req-4"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.PARAM_ERROR.getCode());
        verify(inventoryClient, never()).reserve(anyLong(), any());
    }

    @Test
    void atModeDelegatesToTheAtPlacerWithoutMqSideEffects() {
        OrderService atService = new OrderService(idGenerator, productGuard, inventoryClient,
                orderMapper, txMessageMapper, transactionTemplate, publisher, atOrderPlacer, 3, 1800, "at", true);
        when(atOrderPlacer.placeAt(eq(7L), any())).thenReturn(9999L);

        long orderId = atService.placeOrder(7L, new PlaceOrderRequest(1L, 1), "req-at");

        assertThat(orderId).isEqualTo(9999L);
        verify(publisher, never()).sendStockReservedTransactionally(anyString(), anyString());
        verify(publisher, never()).sendCloseTimeout(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void atModeWithoutSeataEnabledRefusesToBoot() {
        assertThatThrownBy(() -> new OrderService(idGenerator, productGuard, inventoryClient,
                orderMapper, txMessageMapper, transactionTemplate, publisher, atOrderPlacer, 3, 1800, "at", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seata.enabled");
    }

    @Test
    void markPaidDelegatesToGuardedTransition() {
        when(orderMapper.transition(1001L, Order.STATUS_CREATED, Order.STATUS_PAID)).thenReturn(1);
        assertThat(service.markPaid(1001L)).isTrue();

        when(orderMapper.transition(1001L, Order.STATUS_CREATED, Order.STATUS_PAID)).thenReturn(0);
        assertThat(service.markPaid(1001L)).isFalse();
    }

    @Test
    void closePendingOrderRollsStockBackAndMarksReleased() {
        when(orderMapper.transition(1001L, Order.STATUS_CREATED, Order.STATUS_CLOSED)).thenReturn(1);
        Order order = new Order();
        order.setId(1001L);
        order.setSkuId(1L);
        order.setQuantity(2);
        order.setStatus(Order.STATUS_CREATED);
        order.setTxMode("mq");
        when(orderMapper.selectById(1001L)).thenReturn(order);

        assertThat(service.closeIfPending(1001L)).isTrue();
        verify(inventoryClient).rollback(eq(1L), any());
        verify(orderMapper).markStockReleased(1001L);
    }

    @Test
    void closingPaidOrderChangesNothing() {
        Order paid = new Order();
        paid.setId(1001L);
        paid.setStatus(Order.STATUS_PAID);
        when(orderMapper.selectById(1001L)).thenReturn(paid);
        when(orderMapper.transition(1001L, Order.STATUS_CREATED, Order.STATUS_CLOSED)).thenReturn(0);

        assertThat(service.closeIfPending(1001L)).isFalse();
        verify(inventoryClient, never()).rollback(anyLong(), any());
        verify(orderMapper, never()).markStockReleased(anyLong());
    }

    @Test
    void closedButUnreleasedOrderIsCompensatedOnReentry() {
        Order stranded = new Order();
        stranded.setId(1001L);
        stranded.setSkuId(1L);
        stranded.setQuantity(2);
        stranded.setStatus(Order.STATUS_CLOSED);
        stranded.setTxMode("mq");
        when(orderMapper.selectById(1001L)).thenReturn(stranded);
        when(orderMapper.transition(1001L, Order.STATUS_CREATED, Order.STATUS_CLOSED)).thenReturn(0);

        assertThat(service.closeIfPending(1001L)).isFalse();
        verify(inventoryClient).rollback(eq(1L), any());
        verify(orderMapper).markStockReleased(1001L);
    }

    @Test
    void releaseFailureKeepsTheMarkerUnsetSoRetriesCanFinish() {
        when(orderMapper.transition(1001L, Order.STATUS_CREATED, Order.STATUS_CLOSED)).thenReturn(1);
        Order order = new Order();
        order.setId(1001L);
        order.setSkuId(1L);
        order.setQuantity(2);
        order.setStatus(Order.STATUS_CREATED);
        when(orderMapper.selectById(1001L)).thenReturn(order);
        when(inventoryClient.rollback(eq(1L), any())).thenThrow(new RuntimeException("inventory down"));

        assertThatThrownBy(() -> service.closeIfPending(1001L)).isInstanceOf(RuntimeException.class);
        verify(orderMapper, never()).markStockReleased(anyLong());
    }

    @Test
    void otherUsersOrderIsInvisible() {
        Order foreign = new Order();
        foreign.setId(1001L);
        foreign.setUserId(999L);
        when(orderMapper.selectById(1001L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.getOrder(7L, 1001L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.NOT_FOUND.getCode());
    }
}
