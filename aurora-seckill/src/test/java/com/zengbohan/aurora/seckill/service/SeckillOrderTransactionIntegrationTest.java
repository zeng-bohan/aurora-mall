package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 MySQL 验证：去重行、订单、DB 库存扣减必须在同一事务里提交或回滚。
 * 没有 MySQL 时跳过（CI 的纯单元 job 仍能跑）；本地与带中间件的 CI 会执行。
 */
@SpringBootTest
@ActiveProfiles("test")
class SeckillOrderTransactionIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("MYSQL_TEST_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_TEST_PORT", "13306"));

    @Autowired
    private SeckillOrderWriter writer;
    @Autowired
    private JdbcTemplate jdbc;

    private long activityId;
    private String messageId;

    private static boolean mysqlReachable() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(HOST, PORT), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(mysqlReachable(), "mysql not reachable at " + HOST + ":" + PORT);
        messageId = "it-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO seckill_activity
                    (title, sku_id, seckill_price, total_stock, per_user_limit, start_at, end_at)
                VALUES (?, 1, 9.90, 5, 1, ?, ?)
                """, "it-" + UUID.randomUUID(), LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusMinutes(30));
        activityId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO seckill_stock (activity_id, total, available) VALUES (?, 5, 5)", activityId);
    }

    @AfterEach
    void tearDown() {
        if (activityId == 0) {
            return;
        }
        jdbc.update("DELETE FROM seckill_order WHERE activity_id = ?", activityId);
        jdbc.update("DELETE FROM seckill_stock WHERE activity_id = ?", activityId);
        jdbc.update("DELETE FROM seckill_activity WHERE id = ?", activityId);
        jdbc.update("DELETE FROM idempotent_record WHERE biz_type = ? AND biz_key = ?",
                SeckillOrderWriter.DEDUP_BIZ_TYPE, messageId);
    }

    @Test
    void successfulPlaceCommitsDedupAndOrderTogether() {
        SeckillOrderWriter.PlaceOutcome outcome = writer.placeOnce(messageId, activity(), 42L);

        assertThat(outcome.duplicate()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM seckill_order WHERE activity_id = ?", activityId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotent_record WHERE biz_type = ? AND biz_key = ?",
                SeckillOrderWriter.DEDUP_BIZ_TYPE, messageId)).isEqualTo(1);
    }

    @Test
    void failedStockDeductionRollsBackDedupRow() {
        jdbc.update("UPDATE seckill_stock SET available = 0 WHERE activity_id = ?", activityId);

        assertThatThrownBy(() -> writer.placeOnce(messageId, activity(), 42L))
                .isInstanceOf(BusinessException.class);
        assertThat(count("SELECT COUNT(*) FROM seckill_order WHERE activity_id = ?", activityId))
                .as("订单必须回滚").isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotent_record WHERE biz_type = ? AND biz_key = ?",
                SeckillOrderWriter.DEDUP_BIZ_TYPE, messageId))
                .as("去重行必须随订单一起回滚，MQ 重投递才能重试").isZero();
    }

    @Test
    void replayingTheSameMessageIsSkippedOnlyAfterTheOrderCommitted() {
        assertThat(writer.placeOnce(messageId, activity(), 42L).duplicate()).isFalse();

        SeckillOrderWriter.PlaceOutcome replay = writer.placeOnce(messageId, activity(), 42L);

        assertThat(replay.duplicate()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM seckill_order WHERE activity_id = ?", activityId)).isEqualTo(1);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private SeckillActivity activity() {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(activityId);
        activity.setSkuId(1L);
        activity.setSeckillPrice(new BigDecimal("9.90"));
        return activity;
    }
}
