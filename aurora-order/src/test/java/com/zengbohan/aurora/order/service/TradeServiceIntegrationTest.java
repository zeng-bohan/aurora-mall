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
import com.zengbohan.aurora.order.dto.TradeDtos.RefundApplyRequest;
import com.zengbohan.aurora.order.dto.TradeDtos.ShipRequest;
import com.zengbohan.aurora.order.entity.TradeOrder;
import com.zengbohan.aurora.order.entity.TradeOrderItem;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final long SKU_B = 9_600_002L;
    private static final long ADDRESS_ID = 9_700_001L;
    private static final long TRADE_ID = 7_000_001L;
    private static final String ITEM_TITLE = "Aurora Halo 桌面灯";

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("com.zengbohan.aurora.order.mapper")
    @Import({TradePricingService.class, CouponService.class, RefundService.class})
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
    private RefundService refundService;
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
        when(inventoryClient.restore(any())).thenReturn(Result.ok());
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
    void replayStillReturnsTheOriginalTradeAfterCouponIsLockedAndSkuGoesOffShelf() {
        jdbc.update("INSERT INTO coupon_template (id, title, threshold_amount, discount_amount, "
                        + "total, claimed, claim_start_at, claim_end_at, valid_days) "
                        + "VALUES (?, '测试满减', 100.00, 10.00, 10, 1, NOW() - INTERVAL 1 DAY, "
                        + "NOW() + INTERVAL 1 DAY, 30)",
                7_000_050L);
        jdbc.update("INSERT INTO user_coupon (id, template_id, user_id, status, title, "
                        + "threshold_amount, discount_amount, expire_at) "
                        + "VALUES (?, ?, ?, 'UNUSED', '测试满减', 100.00, 10.00, NOW() + INTERVAL 1 DAY)",
                7_000_051L, 7_000_050L, USER_ID);
        PlaceTradeRequest withCoupon = new PlaceTradeRequest(
                List.of(new ItemRequest(SKU_ID, 1)), ADDRESS_ID, 7_000_051L);

        long first = tradeService.place(USER_ID, withCoupon, "idem-8");
        when(productClient.batch(any())).thenReturn(Result.ok(List.of(offShelfSnapshot())));
        long second = tradeService.place(USER_ID, withCoupon, "idem-8");

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT status FROM user_coupon WHERE id = ?",
                String.class, 7_000_051L)).isEqualTo("LOCKED");
    }

    @Test
    void itemOrderDoesNotChangeTheIdempotencyFingerprint() {
        when(productClient.batch(any())).thenReturn(Result.ok(List.of(
                snapshot(SKU_ID, "AUR-HALO-01", 9_500_001L),
                snapshot(SKU_B, "AUR-HALO-02", 9_500_002L))));
        PlaceTradeRequest forward = new PlaceTradeRequest(List.of(
                new ItemRequest(SKU_ID, 1), new ItemRequest(SKU_B, 1)), ADDRESS_ID, null);
        PlaceTradeRequest reversed = new PlaceTradeRequest(List.of(
                new ItemRequest(SKU_B, 1), new ItemRequest(SKU_ID, 1)), ADDRESS_ID, null);

        long first = tradeService.place(USER_ID, forward, "idem-11");
        long second = tradeService.place(USER_ID, reversed, "idem-11");

        assertThat(second).isEqualTo(first);
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


    @Test
    void closeFailureLeavesOrderClosedButReleasableForRetry() {
        when(inventoryClient.release(any())).thenReturn(Result.fail(ErrorCode.SYSTEM_ERROR));
        long tradeId = tradeService.place(USER_ID, request(ADDRESS_ID, 1), "idem-9");

        assertThatThrownBy(() -> tradeService.closeIfPending(tradeId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR));
        TradeOrder closed = tradeMapper.selectById(tradeId);
        assertThat(closed.getStatus()).isEqualTo(TradeOrder.STATUS_CLOSED);
        assertThat(closed.getStockReleased()).isZero();

        when(inventoryClient.release(any())).thenReturn(Result.ok());
        assertThat(tradeService.closeIfPending(tradeId)).isFalse();
        assertThat(tradeMapper.selectById(tradeId).getStockReleased()).isEqualTo(1);
    }

    @Test
    void closingUnpaidSeckillOrderReleasesActivityStock() {
        when(productClient.snapshot(SKU_ID)).thenReturn(Result.ok(snapshot()));
        when(addressClient.defaultAddress(USER_ID)).thenReturn(Result.ok(address()));
        when(seckillClient.release(any())).thenReturn(Result.ok());
        long tradeId = tradeService.placeSeckill(new com.zengbohan.aurora.order.dto.TradeDtos.PlaceSeckillRequest(
                USER_ID, 9_911_001L, SKU_ID, new BigDecimal("149.00"), "seckill-test"));

        assertThat(tradeService.closeIfPending(tradeId)).isTrue();

        assertThat(tradeMapper.selectById(tradeId).getStatus()).isEqualTo(TradeOrder.STATUS_CLOSED);
        verify(seckillClient).release(org.mockito.ArgumentMatchers.argThat(
                request -> request.tradeId() == tradeId && request.activityId() == 9_911_001L
                        && request.quantity() == 1 && "CLOSE".equals(request.reason())));
    }

    @Test
    void partialRefundApprovalUpdatesItemsAndTradeOnlyOnce() {
        when(productClient.batch(any())).thenReturn(Result.ok(List.of(
                snapshot(SKU_ID, "AUR-HALO-01", 9_500_001L),
                snapshot(SKU_B, "AUR-HALO-02", 9_500_002L))));
        long tradeId = tradeService.place(USER_ID,
                new PlaceTradeRequest(List.of(
                        new ItemRequest(SKU_ID, 1), new ItemRequest(SKU_B, 1)),
                        ADDRESS_ID, null), "idem-20");
        tradeService.markPaid(tradeId, new BigDecimal("251.00"));
        List<TradeOrderItem> items = tradeService.items(tradeId);
        long refundId = refundService.apply(USER_ID, tradeId,
                new RefundApplyRequest(List.of(items.get(0).getId()), "只退第一件")).refundId();
        when(paymentClient.refund(anyLong(), any())).thenReturn(Result.ok(
                new com.zengbohan.aurora.api.order.PaymentSummary(1L, tradeId, "A" + tradeId,
                        new BigDecimal("119.50"), new BigDecimal("119.50"),
                        com.zengbohan.aurora.api.order.PaymentSummary.STATUS_PARTIALLY_REFUNDED)));

        refundService.approve(refundId, "admin");
        refundService.approve(refundId, "admin");

        TradeOrder trade = tradeMapper.selectById(tradeId);
        assertThat(trade.getRefundedAmount()).isEqualByComparingTo("119.50");
        assertThat(trade.getRefundStatus()).isEqualTo(TradeOrder.REFUND_PARTIAL);
        assertThat(trade.getStatus()).isEqualTo(TradeOrder.STATUS_PAID);
        List<TradeOrderItem> after = tradeService.items(tradeId);
        assertThat(after.get(0).getStatus()).isEqualTo(TradeOrderItem.STATUS_REFUNDED);
        assertThat(after.get(0).getRefundedAmount()).isEqualByComparingTo("119.50");
        assertThat(after.get(1).getStatus()).isEqualTo(TradeOrderItem.STATUS_NORMAL);
        verify(paymentClient, times(1)).refund(anyLong(), any());
        verify(inventoryClient, times(1)).restore(any());
    }

    @Test
    void fullRefundMarksTradeRefundedAndReturnsTheCoupon() {
        seedCoupon();
        long tradeId = tradeService.place(USER_ID,
                new PlaceTradeRequest(List.of(new ItemRequest(SKU_ID, 1)), ADDRESS_ID, 7_000_051L),
                "idem-21");
        tradeService.markPaid(tradeId, new BigDecimal("121.50"));
        long itemId = tradeService.items(tradeId).get(0).getId();
        long refundId = refundService.apply(USER_ID, tradeId,
                new RefundApplyRequest(List.of(itemId), "整单退款")).refundId();
        when(paymentClient.refund(anyLong(), any())).thenReturn(Result.ok(
                new com.zengbohan.aurora.api.order.PaymentSummary(1L, tradeId, "A" + tradeId,
                        new BigDecimal("121.50"), new BigDecimal("121.50"),
                        com.zengbohan.aurora.api.order.PaymentSummary.STATUS_REFUNDED)));

        var view = refundService.approve(refundId, "admin");

        assertThat(view.settled()).isTrue();
        TradeOrder trade = tradeMapper.selectById(tradeId);
        assertThat(trade.getStatus()).isEqualTo(TradeOrder.STATUS_REFUNDED);
        assertThat(trade.getRefundStatus()).isEqualTo(TradeOrder.REFUND_FULL);
        assertThat(trade.getRefundedAmount()).isEqualByComparingTo("121.50");
        assertThat(jdbc.queryForObject("SELECT status FROM user_coupon WHERE id = ?",
                String.class, 7_000_051L)).isEqualTo("UNUSED");
        verify(paymentClient).refund(anyLong(), org.mockito.ArgumentMatchers.argThat(
                request -> request.refundId() == refundId
                        && request.amount().compareTo(new BigDecimal("121.50")) == 0));
    }

    @Test
    void settlementFailureLeavesApprovedRefundRetryable() {
        long tradeId = tradeService.place(USER_ID, request(ADDRESS_ID, 1), "idem-22");
        tradeService.markPaid(tradeId, new BigDecimal("131.50"));
        long itemId = tradeService.items(tradeId).get(0).getId();
        long refundId = refundService.apply(USER_ID, tradeId,
                new RefundApplyRequest(List.of(itemId), "结算失败测试")).refundId();
        when(paymentClient.refund(anyLong(), any()))
                .thenReturn(Result.fail(ErrorCode.SYSTEM_ERROR))
                .thenReturn(Result.ok(new com.zengbohan.aurora.api.order.PaymentSummary(
                        1L, tradeId, "A" + tradeId, new BigDecimal("131.50"),
                        new BigDecimal("131.50"),
                        com.zengbohan.aurora.api.order.PaymentSummary.STATUS_REFUNDED)));

        assertThat(refundService.approve(refundId, "admin").settled()).isFalse();

        refundService.settle(refundId);

        assertThat(refundService.view(refundId).settled()).isTrue();
        verify(paymentClient, times(2)).refund(anyLong(), any());
        verify(inventoryClient, times(1)).restore(any());
    }

    @Test
    void rejectedRefundRestoresItemsAndAllowsShipping() {
        long tradeId = tradeService.place(USER_ID, request(ADDRESS_ID, 1), "idem-23");
        tradeService.markPaid(tradeId, new BigDecimal("131.50"));
        long itemId = tradeService.items(tradeId).get(0).getId();
        long refundId = refundService.apply(USER_ID, tradeId,
                new RefundApplyRequest(List.of(itemId), "申请后撤销")).refundId();
        assertThatThrownBy(() -> tradeService.ship(tradeId,
                new ShipRequest("顺丰速运", "SF-REJECT")))
                .isInstanceOf(BusinessException.class);

        var view = refundService.reject(refundId, "商品无需退款", "admin");

        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(tradeService.items(tradeId).get(0).getStatus())
                .isEqualTo(TradeOrderItem.STATUS_NORMAL);
        tradeService.ship(tradeId, new ShipRequest("顺丰速运", "SF-REJECT"));
        assertThat(tradeMapper.selectById(tradeId).getStatus()).isEqualTo(TradeOrder.STATUS_SHIPPED);
    }

    @Test
    void partialRefundWithCouponKeepsItemDiscountsEqualToOrderDiscount() {
        seedCoupon();
        when(productClient.batch(any())).thenReturn(Result.ok(List.of(
                snapshot(SKU_ID, "AUR-HALO-01", 9_500_001L),
                snapshot(SKU_B, "AUR-HALO-02", 9_500_002L))));
        long tradeId = tradeService.place(USER_ID,
                new PlaceTradeRequest(List.of(
                        new ItemRequest(SKU_ID, 3), new ItemRequest(SKU_B, 1)),
                        ADDRESS_ID, 7_000_051L), "idem-25");
        tradeService.markPaid(tradeId, new BigDecimal("468.00"));
        List<TradeOrderItem> items = tradeService.items(tradeId);
        long refundId = refundService.apply(USER_ID, tradeId,
                new RefundApplyRequest(List.of(items.get(1).getId()), "退掉第二件")).refundId();
        when(paymentClient.refund(anyLong(), any())).thenReturn(Result.ok(
                new com.zengbohan.aurora.api.order.PaymentSummary(1L, tradeId, "A" + tradeId,
                        new BigDecimal("119.50"), new BigDecimal("119.50"),
                        com.zengbohan.aurora.api.order.PaymentSummary.STATUS_PARTIALLY_REFUNDED)));

        refundService.approve(refundId, "admin");

        List<TradeOrderItem> after = tradeService.items(tradeId);
        assertThat(after.get(1).getDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(after.get(0).getDiscountAmount()).isEqualByComparingTo("10.00");
        assertThat(after.stream().map(TradeOrderItem::getDiscountAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("10.00");
        assertThat(tradeMapper.selectById(tradeId).getRefundedAmount())
                .isEqualByComparingTo("119.50");
    }

    @Test
    void refundForItemOfAnotherOrderIsRejected() {
        long tradeId = tradeService.place(USER_ID, request(ADDRESS_ID, 1), "idem-24");
        tradeService.markPaid(tradeId, new BigDecimal("131.50"));

        assertThatThrownBy(() -> refundService.apply(USER_ID, tradeId,
                new RefundApplyRequest(List.of(999_999_999L), "非法商品")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("不属于该订单"));
    }

    private void seedCoupon() {
        jdbc.update("INSERT INTO coupon_template (id, title, threshold_amount, discount_amount, "
                        + "total, claimed, claim_start_at, claim_end_at, valid_days) "
                        + "VALUES (?, '测试满减', 100.00, 10.00, 10, 1, NOW() - INTERVAL 1 DAY, "
                        + "NOW() + INTERVAL 1 DAY, 30)",
                7_000_050L);
        jdbc.update("INSERT INTO user_coupon (id, template_id, user_id, status, title, "
                        + "threshold_amount, discount_amount, expire_at) "
                        + "VALUES (?, ?, ?, 'UNUSED', '测试满减', 100.00, 10.00, NOW() + INTERVAL 1 DAY)",
                7_000_051L, 7_000_050L, USER_ID);
    }

    @Test
    void stateMachineRejectsSkippedAndRepeatedTransitions() {
        long tradeId = tradeService.place(USER_ID, request(ADDRESS_ID, 1), "idem-26");

        assertThatThrownBy(() -> tradeService.confirmReceipt(USER_ID, tradeId))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tradeService.ship(tradeId,
                new ShipRequest("顺丰速运", "SF-SKIP")))
                .isInstanceOf(BusinessException.class);

        tradeService.markPaid(tradeId, new BigDecimal("131.50"));
        tradeService.ship(tradeId, new ShipRequest("顺丰速运", "SF-ONCE"));
        assertThatThrownBy(() -> tradeService.ship(tradeId,
                new ShipRequest("顺丰速运", "SF-TWICE")))
                .isInstanceOf(BusinessException.class);

        tradeService.confirmReceipt(USER_ID, tradeId);
        assertThatThrownBy(() -> tradeService.confirmReceipt(USER_ID, tradeId))
                .isInstanceOf(BusinessException.class);
        assertThat(tradeService.closeIfPending(tradeId)).isFalse();
        assertThat(tradeMapper.selectById(tradeId).getStatus())
                .isEqualTo(TradeOrder.STATUS_COMPLETED);
    }

    @Test
    void refundBeforePaymentIsRejected() {
        long tradeId = tradeService.place(USER_ID, request(ADDRESS_ID, 1), "idem-27");
        long itemId = tradeService.items(tradeId).get(0).getId();

        assertThatThrownBy(() -> refundService.apply(USER_ID, tradeId,
                new RefundApplyRequest(List.of(itemId), "未支付退款")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("当前订单状态不支持退款"));
    }

    @Test
    void concurrentRefundApplicationsOnlyCreateOnePendingRefund() throws Exception {        when(productClient.batch(any())).thenReturn(Result.ok(List.of(
                snapshot(SKU_ID, "AUR-HALO-01", 9_500_001L),
                snapshot(SKU_B, "AUR-HALO-02", 9_500_002L))));
        long tradeId = tradeService.place(USER_ID,
                new PlaceTradeRequest(List.of(
                        new ItemRequest(SKU_ID, 1), new ItemRequest(SKU_B, 1)),
                        ADDRESS_ID, null), "idem-10");
        tradeService.markPaid(tradeId, new BigDecimal("251.00"));
        List<TradeOrderItem> items = tradeService.items(tradeId);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (TradeOrderItem item : items) {
                pool.submit(() -> {
                    try {
                        start.await();
                        refundService.apply(USER_ID, tradeId,
                                new RefundApplyRequest(List.of(item.getId()), "并发退款测试"));
                        succeeded.incrementAndGet();
                    } catch (BusinessException e) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_refund WHERE trade_id = ? AND status = 'PENDING'",
                Integer.class, tradeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_order_item WHERE trade_id = ? AND status = 'REFUNDING'",
                Integer.class, tradeId)).isEqualTo(1);
    }

    private static PlaceTradeRequest request(long addressId, int quantity) {
        return new PlaceTradeRequest(List.of(new ItemRequest(SKU_ID, quantity)), addressId, null);
    }

    private static ProductSnapshot snapshot() {
        return snapshot(SKU_ID, "AUR-HALO-01", 9_500_001L);
    }

    private static ProductSnapshot snapshot(long skuId, String skuCode, long spuId) {
        return new ProductSnapshot(skuId, spuId, skuCode, ITEM_TITLE,
                "雾白 / 标准版", "/products/p01-1.webp", new BigDecimal("119.50"),
                20, ProductSnapshot.STATUS_ON_SALE);
    }

    private static ProductSnapshot offShelfSnapshot() {
        return new ProductSnapshot(SKU_ID, 9_500_001L, "AUR-HALO-01", ITEM_TITLE,
                "雾白 / 标准版", "/products/p01-1.webp", new BigDecimal("119.50"),
                20, ProductSnapshot.STATUS_OFF_SHELF);
    }

    private static AddressClient.AddressView address() {
        return new AddressClient.AddressView(ADDRESS_ID, USER_ID, "林一", "13800000001",
                "江苏省", "南京市", "鼓楼区", "中山北路 88 号 1201 室", "210000", true);
    }

    private void clean() {
        jdbc.update("DELETE FROM user_coupon WHERE id = 7000051");
        jdbc.update("DELETE FROM coupon_template WHERE id = 7000050");
        jdbc.update("DELETE FROM trade_order_item WHERE trade_id BETWEEN 7000000 AND 7000100");
        jdbc.update("DELETE FROM order_refund_item WHERE refund_id BETWEEN 7000000 AND 7000100");
        jdbc.update("DELETE FROM order_refund WHERE trade_id BETWEEN 7000000 AND 7000100");
        jdbc.update("DELETE FROM order_idempotency WHERE trade_id BETWEEN 7000000 AND 7000100");
        jdbc.update("DELETE FROM tx_message WHERE biz_key BETWEEN '7000000' AND '7000100'");
        jdbc.update("DELETE FROM trade_order WHERE id BETWEEN 7000000 AND 7000100");
    }
}
