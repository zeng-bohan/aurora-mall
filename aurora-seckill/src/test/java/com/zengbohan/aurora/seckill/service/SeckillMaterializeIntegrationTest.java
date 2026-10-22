package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.seckill.client.OrderClient;
import com.zengbohan.aurora.seckill.client.ProductClient;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MQ 模式下的重复投递与落单失败：真实 MySQL + Redis，订单服务用桩替换。
 */
@SpringBootTest(classes = SeckillMaterializeIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "aurora.seckill.order-mode=mq")
@ActiveProfiles("test")
class SeckillMaterializeIntegrationTest {

    private static final long ACTIVITY = 9_930_201L;
    private static final long SKU_ID = 9_900_045L;
    private static final long USER = 701L;

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

    @BeforeEach
    void setUp() {
        clean();
        when(publisher.sendOrderRequest(any(), anyLong(), anyLong()))
                .thenReturn(SeckillEventPublisher.DispatchResult.ACCEPTED);
        when(orderClient.find(anyLong(), anyLong())).thenReturn(Result.fail(ErrorCode.NOT_FOUND));
        seedActivity(3);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void duplicateMessageDeliveryCreatesOnlyOneOrder() {
        when(orderClient.place(any())).thenReturn(Result.ok(9_950_001L));
        assertThat(orderService.buy(ACTIVITY, USER).status()).isEqualTo("QUEUED");

        orderService.materialize("msg-1", ACTIVITY, USER);
        orderService.materialize("msg-1", ACTIVITY, USER);
        orderService.materialize("msg-2", ACTIVITY, USER);

        verify(orderClient, times(1)).place(any());
        assertThat(redisStock()).isEqualTo(2);
        assertThat(reservationStatus()).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id = ?", Integer.class,
                ACTIVITY)).isEqualTo(1);
    }

    @Test
    void orderFailureCompensatesStockAndRedeliveryDoesNotDoubleRestore() {
        when(orderClient.place(any())).thenReturn(Result.fail(ErrorCode.SYSTEM_ERROR));
        assertThat(orderService.buy(ACTIVITY, USER).status()).isEqualTo("QUEUED");

        orderService.materialize("msg-fail", ACTIVITY, USER);
        orderService.materialize("msg-fail", ACTIVITY, USER);

        assertThat(redisStock()).isEqualTo(3);
        assertThat(dbStock()).isEqualTo(3);
        assertThat(reservationStatus()).isEqualTo("RELEASED");
        assertThat(redis.opsForHash().get(SeckillLuaScripts.resultKey(ACTIVITY), String.valueOf(USER)))
                .asString().startsWith("FAIL:");
        assertThat(redis.opsForSet().isMember(
                SeckillLuaScripts.boughtKey(ACTIVITY), String.valueOf(USER))).isFalse();
    }

    private void seedActivity(int stock) {
        jdbc.update("INSERT INTO seckill_activity (id, title, spu_id, sku_id, product_title, "
                        + "product_image, original_price, seckill_price, total_stock, per_user_limit, "
                        + "start_at, end_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ACTIVITY, "MQ 测试秒杀", 9_800_001L, SKU_ID, "Aurora 测试商品",
                "/products/p01-1.webp", new BigDecimal("999.00"), new BigDecimal("699.00"),
                stock, 1, Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),
                Timestamp.valueOf(LocalDateTime.now().plusHours(1)));
        jdbc.update("INSERT INTO seckill_stock (activity_id, total, available) VALUES (?, ?, ?)",
                ACTIVITY, stock, stock);
        preheatService.preheat(ACTIVITY);
    }

    private void clean() {
        jdbc.update("DELETE FROM seckill_reservation WHERE activity_id = ?", ACTIVITY);
        jdbc.update("DELETE FROM seckill_release WHERE activity_id = ?", ACTIVITY);
        jdbc.update("DELETE FROM seckill_stock WHERE activity_id = ?", ACTIVITY);
        jdbc.update("DELETE FROM seckill_activity WHERE id = ?", ACTIVITY);
        redis.delete(List.of(SeckillLuaScripts.activityKey(ACTIVITY),
                SeckillLuaScripts.boughtKey(ACTIVITY),
                SeckillLuaScripts.resultKey(ACTIVITY)));
    }

    private long redisStock() {
        Object value = redis.opsForHash().get(SeckillLuaScripts.activityKey(ACTIVITY), "stock");
        assertThat(value).isNotNull();
        return Long.parseLong(value.toString());
    }

    private int dbStock() {
        return jdbc.queryForObject("SELECT available FROM seckill_stock WHERE activity_id = ?",
                Integer.class, ACTIVITY);
    }

    private String reservationStatus() {
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM seckill_reservation WHERE activity_id = ? AND user_id = ?",
                String.class, ACTIVITY, USER);
        return statuses.isEmpty() ? null : statuses.get(0);
    }
}
