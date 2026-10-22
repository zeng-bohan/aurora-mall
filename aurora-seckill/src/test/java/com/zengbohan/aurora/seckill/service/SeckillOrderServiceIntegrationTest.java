package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.seckill.client.OrderClient;
import com.zengbohan.aurora.seckill.client.ProductClient;
import com.zengbohan.aurora.seckill.dto.SeckillBuyView;
import com.zengbohan.aurora.seckill.mq.SeckillEventPublisher;
import com.zengbohan.aurora.seckill.ratelimit.SeckillRateLimitProperties;
import com.zengbohan.aurora.seckill.ratelimit.SeckillRateLimiter;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 秒杀预扣、一人一单、售罄与退款归还的真实 MySQL + Redis 用例。
 * 订单服务用桩替换，其余（Lua、库存、预约台账）全部走真实中间件。
 */
@SpringBootTest(classes = SeckillOrderServiceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "aurora.seckill.order-mode=sync")
@ActiveProfiles("test")
class SeckillOrderServiceIntegrationTest {

    private static final long ACTIVITY_RUNNING = 9_930_001L;
    private static final long ACTIVITY_NOT_STARTED = 9_930_002L;
    private static final long SKU_ID = 9_900_045L;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("com.zengbohan.aurora.seckill.mapper")
    @Import({SeckillActivityService.class, SeckillPreheatService.class,
            SeckillOrderService.class, SeckillLuaScripts.class, SeckillRateLimiter.class})
    static class TestApplication {

        @Bean
        SeckillRateLimitProperties rateLimitProperties() {
            SeckillRateLimitProperties properties = new SeckillRateLimitProperties();
            properties.setEnabled(false);
            return properties;
        }
    }

    @Autowired
    private SeckillOrderService orderService;
    @Autowired
    private SeckillPreheatService preheatService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private OrderClient orderClient;
    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private SeckillEventPublisher publisher;

    private final AtomicLong tradeSequence = new AtomicLong(9_940_000L);

    @BeforeEach
    void setUp() {
        clean();
        when(orderClient.find(anyLong(), anyLong())).thenReturn(Result.fail(ErrorCode.NOT_FOUND));
        when(orderClient.place(any())).thenAnswer(
                invocation -> Result.ok(tradeSequence.incrementAndGet()));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void syncBuyConsumesReservationAndRejectsSecondAttempt() {
        seedActivity(ACTIVITY_RUNNING, 3, LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusHours(1));
        preheatService.preheat(ACTIVITY_RUNNING);

        SeckillBuyView view = orderService.buy(ACTIVITY_RUNNING, 101L);

        assertThat(view.status()).isEqualTo(SeckillBuyView.STATUS_PLACED);
        assertThat(view.tradeId()).isNotNull();
        assertThat(redisStock(ACTIVITY_RUNNING)).isEqualTo(2);
        assertThat(redis.opsForSet().isMember(
                SeckillLuaScripts.boughtKey(ACTIVITY_RUNNING), "101")).isTrue();
        assertThat(redis.opsForHash().get(
                SeckillLuaScripts.resultKey(ACTIVITY_RUNNING), "101"))
                .isEqualTo("ORDER:" + view.tradeId());
        assertThat(dbStock(ACTIVITY_RUNNING)).isEqualTo(2);
        assertThat(reservationStatus(ACTIVITY_RUNNING, 101L)).isEqualTo("CONSUMED");

        assertThatThrownBy(() -> orderService.buy(ACTIVITY_RUNNING, 101L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SECKILL_ALREADY_BOUGHT));
        assertThat(redisStock(ACTIVITY_RUNNING)).isEqualTo(2);
        assertThat(dbStock(ACTIVITY_RUNNING)).isEqualTo(2);
    }

    @Test
    void soldOutActivityRejectsEveryLaterBuyer() {
        seedActivity(ACTIVITY_RUNNING, 1, LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusHours(1));
        preheatService.preheat(ACTIVITY_RUNNING);

        assertThat(orderService.buy(ACTIVITY_RUNNING, 201L).status())
                .isEqualTo(SeckillBuyView.STATUS_PLACED);
        assertThatThrownBy(() -> orderService.buy(ACTIVITY_RUNNING, 202L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SECKILL_SOLD_OUT));

        assertThat(redisStock(ACTIVITY_RUNNING)).isZero();
        assertThat(dbStock(ACTIVITY_RUNNING)).isZero();
        assertThat(reservationStatus(ACTIVITY_RUNNING, 202L)).isNull();
    }

    @Test
    void notStartedActivityDoesNotTouchStock() {
        seedActivity(ACTIVITY_NOT_STARTED, 2, LocalDateTime.now().plusMinutes(10),
                LocalDateTime.now().plusHours(1));
        preheatService.preheat(ACTIVITY_NOT_STARTED);

        assertThatThrownBy(() -> orderService.buy(ACTIVITY_NOT_STARTED, 301L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SECKILL_NOT_STARTED));

        assertThat(redisStock(ACTIVITY_NOT_STARTED)).isEqualTo(2);
        assertThat(dbStock(ACTIVITY_NOT_STARTED)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id = ?",
                Integer.class, ACTIVITY_NOT_STARTED)).isZero();
    }

    @Test
    void releaseForTradeRestoresBothStocksExactlyOnce() {
        seedActivity(ACTIVITY_RUNNING, 2, LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusHours(1));
        preheatService.preheat(ACTIVITY_RUNNING);
        long tradeId = orderService.buy(ACTIVITY_RUNNING, 401L).tradeId();

        assertThat(orderService.releaseForTrade(tradeId, ACTIVITY_RUNNING, 1, "REFUND")).isTrue();
        assertThat(orderService.releaseForTrade(tradeId, ACTIVITY_RUNNING, 1, "REFUND")).isFalse();

        assertThat(redisStock(ACTIVITY_RUNNING)).isEqualTo(2);
        assertThat(dbStock(ACTIVITY_RUNNING)).isEqualTo(2);
        assertThat(reservationStatus(ACTIVITY_RUNNING, 401L)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_release WHERE trade_id = ?", Integer.class, tradeId))
                .isEqualTo(1);
    }

    @Test
    void concurrentBuyersNeverOversell() throws Exception {
        seedActivity(ACTIVITY_RUNNING, 2, LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusHours(1));
        preheatService.preheat(ACTIVITY_RUNNING);

        CountDownLatch start = new CountDownLatch(1);
        AtomicLong placed = new AtomicLong();
        AtomicLong soldOut = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            for (int i = 0; i < 5; i++) {
                long userId = 500L + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        orderService.buy(ACTIVITY_RUNNING, userId);
                        placed.incrementAndGet();
                    } catch (BusinessException e) {
                        if (e.getErrorCode() == ErrorCode.SECKILL_SOLD_OUT) {
                            soldOut.incrementAndGet();
                        }
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

        assertThat(placed.get()).isEqualTo(2);
        assertThat(soldOut.get()).isEqualTo(3);
        assertThat(redisStock(ACTIVITY_RUNNING)).isZero();
        assertThat(dbStock(ACTIVITY_RUNNING)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id = ? AND status = 'CONSUMED'",
                Integer.class, ACTIVITY_RUNNING)).isEqualTo(2);
    }

    private void seedActivity(long activityId, int stock, LocalDateTime startAt,
                              LocalDateTime endAt) {
        jdbc.update("INSERT INTO seckill_activity (id, title, spu_id, sku_id, product_title, "
                        + "product_image, original_price, seckill_price, total_stock, per_user_limit, "
                        + "start_at, end_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                activityId, "测试秒杀", 9_800_001L, SKU_ID, "Aurora 测试商品",
                "/products/p01-1.webp", new BigDecimal("999.00"),
                new BigDecimal("699.00"), stock, 1,
                Timestamp.valueOf(startAt), Timestamp.valueOf(endAt));
        jdbc.update("INSERT INTO seckill_stock (activity_id, total, available) VALUES (?, ?, ?)",
                activityId, stock, stock);
    }

    private void clean() {
        for (long activityId : List.of(ACTIVITY_RUNNING, ACTIVITY_NOT_STARTED)) {
            jdbc.update("DELETE FROM seckill_reservation WHERE activity_id = ?", activityId);
            jdbc.update("DELETE FROM seckill_release WHERE activity_id = ?", activityId);
            jdbc.update("DELETE FROM seckill_stock WHERE activity_id = ?", activityId);
            jdbc.update("DELETE FROM seckill_activity WHERE id = ?", activityId);
            redis.delete(List.of(SeckillLuaScripts.activityKey(activityId),
                    SeckillLuaScripts.boughtKey(activityId),
                    SeckillLuaScripts.resultKey(activityId),
                    SeckillRateLimiter.activityKey(activityId)));
        }
    }

    private long redisStock(long activityId) {
        Object value = redis.opsForHash().get(SeckillLuaScripts.activityKey(activityId), "stock");
        assertThat(value).as("preheated seckill stock").isNotNull();
        return Long.parseLong(value.toString());
    }

    private int dbStock(long activityId) {
        return jdbc.queryForObject(
                "SELECT available FROM seckill_stock WHERE activity_id = ?",
                Integer.class, activityId);
    }

    private String reservationStatus(long activityId, long userId) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM seckill_reservation WHERE activity_id = ? AND user_id = ?",
                String.class, activityId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
