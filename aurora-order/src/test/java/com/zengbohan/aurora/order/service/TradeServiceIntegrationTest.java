package com.zengbohan.aurora.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengbohan.aurora.api.product.ProductSnapshot;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.id.SegmentIdGenerator;
import com.zengbohan.aurora.order.client.AddressClient;
import com.zengbohan.aurora.order.client.InventoryClient;
import com.zengbohan.aurora.order.client.PaymentClient;
import com.zengbohan.aurora.order.client.ProductClient;
import com.zengbohan.aurora.order.client.SeckillClient;
import com.zengbohan.aurora.order.client.UserAdminClient;
import com.zengbohan.aurora.order.dto.TradeDtos.ItemRequest;
import com.zengbohan.aurora.order.dto.TradeDtos.PlaceTradeRequest;
import com.zengbohan.aurora.order.dto.TradeDtos.ShipRequest;
import com.zengbohan.aurora.order.entity.TradeOrder;
import com.zengbohan.aurora.order.mapper.OrderIdempotencyMapper;
import com.zengbohan.aurora.order.mapper.OrderRefundItemMapper;
import com.zengbohan.aurora.order.mapper.OrderRefundMapper;
import com.zengbohan.aurora.order.mapper.TradeOrderItemMapper;
import com.zengbohan.aurora.order.mapper.TradeOrderMapper;
import com.zengbohan.aurora.order.mapper.TxMessageMapper;
import com.zengbohan.aurora.order.mq.OrderEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单幂等、事务失败库存补偿、超时关单和关单后迟付的真实 MySQL 用例。
 * 商品/地址/库存/支付等远程调用用桩替换，订单库与本地事务走真实 MySQL。
 */
@SpringBootTest(classes = TradeServiceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TradeServiceIntegrationTest {

    private static final long USER_ID = 1L;
    private static final long SKU_ID = 9_600_001L;
    private static final long ADDRESS_ID = 9_700_001L;
    private static final long TRADE_ID = 7_000_001L;
    private static final String ITEM_TITLE = "Aurora Halo 桌面灯";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("com.zengbohan.aurora.order.mapper")
    @Import({TradePricingService.class, CouponService.class})
    static class TestApplication {

        @Bean
        TradeService tradeService(SegmentIdGenerator idGenerator, TradePricingService pricingService,
                                  CouponService couponService, ProductClient productClient,
                                  AddressClient addressClient, InventoryClient inventoryClient,
                                  PaymentClient paymentClient, SeckillClient seckillClient,
                                  UserAdminClient userAdminClient, TradeOrderMapper tradeMapper,
                                  TradeOrderItemMapper itemMapper, OrderRefundMapper refundMapper,
                                  OrderRefundItemMapper refundItemMapper,
                                  OrderIdempotencyMapper idempotencyMapper,
                                  TxMessageMapper txMessageMapper, OrderEventPublisher publisher,
                                  TransactionTemplate transactionTemplate,
                                  ObjectMapper objectMapper) {
            return new TradeService(idGenerator, pricingService, couponService, productClient,
                    addressClient, inventoryClient, paymentClient, seckillClient, userAdminClient,
                    tradeMapper, itemMapper, refundMapper, refundItemMapper, idempotencyMapper,
                    txMessageMapper, publisher, transactionTemplate, objectMapper, 16, 1800, 7);
        }
    }

    @Autowired
    private TradeService tradeService;
    @Autowired
    private TradeOrderMapper tradeMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private SegmentIdGenerator idGenerator;
    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private AddressClient addressClient;
    @MockitoBean
    private InventoryClient inventoryClient;
    @MockitoBean
    private PaymentClient paymentClient;
    @MockitoBean
    private SeckillClient seckillClient;
    @MockitoBean
    private UserAdminClient userAdminClient;
    @MockitoBean
    private OrderEventPublisher publisher;

    @BeforeEach
    void setUp() {
        clean();
        when(idGenerator.nextId()).thenReturn(TRADE_ID);
        when(productClient.batch(any())).thenReturn(Result.ok(List.of(snapshot())));
        when(addressClient.address(anyLong(), anyLong())).thenReturn(Result.ok(address()));
        when(inventoryClient.reserve(any())).thenReturn(Result.ok());
        when(inventoryClient.confirm(any())).thenReturn(Result.ok());
        when(inventoryClient.release(any())).thenReturn(Result.ok());
        when(paymentClient.lateRefund(anyLong())).thenReturn(Result.ok());
        when(publisher.sendCloseTimeout(anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void duplicateIdempotencyKeyReturnsTheOriginalTradeAndReservesOnce() {
        PlaceTradeRequest request = request(ADDRESS_ID, 2);

        long first = tradeService.place(USER_ID, request, "idem-1");
        long second = tradeService.place(USER_ID, request, "idem-1");

        assertThat(first).isEqualTo(TRADE_ID);
        assertThat(second).isEqualTo(TRADE_ID);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_order WHERE id = ?", Integer.class, TRADE_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_order_item WHERE trade_id = ?", Integer.class, TRADE_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_idempotency WHERE user_id = ? AND idem_key = 'idem-1'",
                Integer.class, USER_ID)).isEqualTo(1);
        verify(inventoryClient, times(1)).reserve(any());
    }

    @Test
    void reusingAKeyForDifferentContentIsRejectedWithoutSecondReserve() {
        tradeService.place(USER_ID, request(ADDRESS_ID, 1), "idem-2");

        assertThatThrownBy(() -> tradeService.place(USER_ID, request(ADDRESS_ID, 3), "idem-2"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_REQUEST));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_order WHERE id = ?", Integer.class, TRADE_ID))
                .isEqualTo(1);
        verify(inventoryClient, times(1)).reserve(any());
    }

    @Test
    void failedLocalTransactionReleasesTheAlreadyReservedStock() {
        when(idGenerator.nextId()).thenReturn(TRADE_ID, TRADE_ID);
        tradeService.place(USER_ID, request(ADDRESS_ID, 1), "idem-3a");

        assertThatThrownBy(() -> tradeService.place(USER_ID, request(ADDRESS_ID, 1), "idem-3b"))
                .isInstanceOf(RuntimeException.class);

        verify(inventoryClient, times(2)).reserve(any());
        verify(inventoryClient).release(any());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_order WHERE id = ?", Integer.class, TRADE_ID))
                .isEqualTo(1);
    }

    @Test
    void closeIfPendingReleasesStockOnceAndMarksTheOrderClosed() {
        long tradeId = tradeService.place(USER_ID, request(ADDRESS_ID, 1), "idem-4");

        assertThat(tradeService.closeIfPending(tradeId)).isTrue();
        assertThat(tradeService.closeIfPending(tradeId)).isFalse();

        TradeOrder closed = tradeMapper.selectById(tradeId);
        assertThat(closed.getStatus()).isEqualTo(TradeOrder.STATUS_CLOSED);
        assertThat(closed.getStockReleased()).isEqualTo(1);
        verify(inventoryClient, times(1)).release(any());
    }

    @Test
    void paymentArrivingAfterCloseIsRefundedInsteadOfConfirmed() {
        long tradeId = tradeService.place(USER_ID, request(ADDRESS_ID, 1), "idem-5");
        tradeService.closeIfPending(tradeId);

        tradeService.markPaid(tradeId, new BigDecimal("131.50"));

        assertThat(tradeMapper.selectById(tradeId).getStatus()).isEqualTo(TradeOrder.STATUS_CLOSED);
        verify(paymentClient).lateRefund(tradeId);
        verify(inventoryClient, never()).confirm(any());
        verify(inventoryClient, times(1)).release(any());
    }

    @Test
    void paidOrderShipsAndConfirmsInOrder() {
        long tradeId = tradeService.place(USER_ID, request(ADDRESS_ID, 1), "idem-6");

        tradeService.markPaid(tradeId, new BigDecimal("131.50"));
        tradeService.ship(tradeId, new ShipRequest("顺丰速运", "SF202610220001"));
        tradeService.confirmReceipt(USER_ID, tradeId);

        TradeOrder completed = tradeMapper.selectById(tradeId);
        assertThat(completed.getStatus()).isEqualTo(TradeOrder.STATUS_COMPLETED);
        assertThat(completed.getCarrier()).isEqualTo("顺丰速运");
        assertThat(completed.getTrackingNo()).isEqualTo("SF202610220001");
        verify(inventoryClient).confirm(any());
    }

    @Test
    void shippingIsBlockedWhileARefundIsPending() {
        long tradeId = tradeService.place(USER_ID, request(ADDRESS_ID, 1), "idem-7");
        tradeService.markPaid(tradeId, new BigDecimal("131.50"));
        jdbc.update("INSERT INTO order_refund (refund_no, trade_id, user_id, amount, reason, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'PENDING')",
                "RTEST-" + tradeId, tradeId, USER_ID, new BigDecimal("131.50"), "测试待审批退款");

        assertThatThrownBy(() -> tradeService.ship(tradeId,
                new ShipRequest("顺丰速运", "SF202610220002")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("待审批退款"));
    }

    private static PlaceTradeRequest request(long addressId, int quantity) {
        return new PlaceTradeRequest(List.of(new ItemRequest(SKU_ID, quantity)), addressId, null);
    }

    private static ProductSnapshot snapshot() {
        return new ProductSnapshot(SKU_ID, 9_500_001L, "AUR-HALO-01", ITEM_TITLE,
                "雾白 / 标准版", "/products/p01-1.webp", new BigDecimal("119.50"),
                20, ProductSnapshot.STATUS_ON_SALE);
    }

    private static AddressClient.AddressView address() {
        return new AddressClient.AddressView(ADDRESS_ID, USER_ID, "林一", "13800000001",
                "江苏省", "南京市", "鼓楼区", "中山北路 88 号 1201 室", "210000", true);
    }

    private void clean() {
        jdbc.update("DELETE FROM trade_order_item WHERE trade_id BETWEEN 7000000 AND 7000100");
        jdbc.update("DELETE FROM order_refund_item WHERE refund_id BETWEEN 7000000 AND 7000100");
        jdbc.update("DELETE FROM order_refund WHERE trade_id BETWEEN 7000000 AND 7000100");
        jdbc.update("DELETE FROM order_idempotency WHERE trade_id BETWEEN 7000000 AND 7000100");
        jdbc.update("DELETE FROM tx_message WHERE biz_key BETWEEN '7000000' AND '7000100'");
        jdbc.update("DELETE FROM trade_order WHERE id BETWEEN 7000000 AND 7000100");
    }
}
