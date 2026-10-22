package com.zengbohan.aurora.payment.service;

import com.zengbohan.aurora.api.order.PaymentSummary;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.payment.channel.ChannelSignatureVerifier;
import com.zengbohan.aurora.payment.client.OrderClient;
import com.zengbohan.aurora.payment.mapper.PaymentOrderMapper;
import com.zengbohan.aurora.payment.mq.PaymentEventPublisher;
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

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 退款台账与支付单的真实 MySQL 用例：部分退款不能被记成整单退款，
 * 失败退款不能留下已落账却未生效的幂等记录。
 */
@SpringBootTest(classes = PaymentRefundIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PaymentRefundIntegrationTest {

    private static final long TRADE_BASE = 9_980_000L;
    private static final long REFUND_BASE = 9_990_000L;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("com.zengbohan.aurora.payment.mapper")
    @Import({PaymentService.class, ChannelSignatureVerifier.class})
    static class TestApplication {
        @Bean
        OrderClient orderClient() {
            return mock(OrderClient.class);
        }

        @Bean
        PaymentEventPublisher paymentEventPublisher() {
            return mock(PaymentEventPublisher.class);
        }
    }

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentOrderMapper paymentMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM payment_refunds WHERE trade_id BETWEEN ? AND ?",
                TRADE_BASE + 1, TRADE_BASE + 20);
        jdbc.update("DELETE FROM payment_orders WHERE trade_id BETWEEN ? AND ?",
                TRADE_BASE + 1, TRADE_BASE + 20);
    }

    @Test
    void partialRefundStaysPartialUntilTheRemainderIsRefunded() {
        long tradeId = TRADE_BASE + 1;
        seedPaid(tradeId, "100.00");

        PaymentSummary first = paymentService.internalRefund(tradeId, REFUND_BASE + 1,
                new BigDecimal("60.00"));
        assertThat(first.refundedAmount()).isEqualByComparingTo("60.00");
        assertThat(first.status()).isEqualTo("PARTIALLY_REFUNDED");

        PaymentSummary second = paymentService.internalRefund(tradeId, REFUND_BASE + 2,
                new BigDecimal("40.00"));
        assertThat(second.refundedAmount()).isEqualByComparingTo("100.00");
        assertThat(second.status()).isEqualTo("REFUNDED");
    }

    @Test
    void failedRefundLeavesNoIdempotencyRecordSoRetryCanSucceed() {
        long tradeId = TRADE_BASE + 2;
        seedPaid(tradeId, "100.00");

        assertThatThrownBy(() -> paymentService.internalRefund(tradeId, REFUND_BASE + 3,
                new BigDecimal("120.00")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds WHERE refund_id = ?", Integer.class,
                REFUND_BASE + 3)).isZero();
        assertThat(paymentMapper.findByTradeId(tradeId).getRefundedAmount())
                .isEqualByComparingTo("0.00");

        PaymentSummary retried = paymentService.internalRefund(tradeId, REFUND_BASE + 3,
                new BigDecimal("100.00"));
        assertThat(retried.status()).isEqualTo("REFUNDED");
        assertThat(retried.refundedAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void duplicateRefundCallbackOnlyAppliesOnce() {
        long tradeId = TRADE_BASE + 3;
        seedPaid(tradeId, "100.00");

        paymentService.internalRefund(tradeId, REFUND_BASE + 4, new BigDecimal("30.00"));
        PaymentSummary replay = paymentService.internalRefund(tradeId, REFUND_BASE + 4,
                new BigDecimal("30.00"));

        assertThat(replay.refundedAmount()).isEqualByComparingTo("30.00");
        assertThat(replay.status()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds WHERE refund_id = ?", Integer.class,
                REFUND_BASE + 4)).isEqualTo(1);
    }

    @Test
    void reusingRefundIdWithAnotherAmountIsRejected() {
        long tradeId = TRADE_BASE + 4;
        seedPaid(tradeId, "100.00");
        paymentService.internalRefund(tradeId, REFUND_BASE + 5, new BigDecimal("20.00"));

        assertThatThrownBy(() -> paymentService.internalRefund(tradeId, REFUND_BASE + 5,
                new BigDecimal("30.00")))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
        assertThat(paymentMapper.findByTradeId(tradeId).getRefundedAmount())
                .isEqualByComparingTo("20.00");
    }

    @Test
    void concurrentRetriesOfOneRefundApplyOnlyOnce() throws Exception {
        long tradeId = TRADE_BASE + 5;
        seedPaid(tradeId, "100.00");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        paymentService.internalRefund(tradeId, REFUND_BASE + 6,
                                new BigDecimal("25.00"));
                        succeeded.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT refunded_amount FROM payment_orders WHERE trade_id = ?",
                BigDecimal.class, tradeId)).isEqualByComparingTo("25.00");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_refunds WHERE refund_id = ?", Integer.class,
                REFUND_BASE + 6)).isEqualTo(1);
    }

    private void seedPaid(long tradeId, String amount) {
        jdbc.update("INSERT INTO payment_orders "
                        + "(trade_id, trade_no, user_id, amount, refunded_amount, status, channel) "
                        + "VALUES (?, ?, 1, ?, 0.00, 'PAID', 'MOCK')",
                tradeId, "A" + tradeId, new BigDecimal(amount));
    }
}
