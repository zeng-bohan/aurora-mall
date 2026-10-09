package com.zengbohan.aurora.order.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 领券的**真实 MySQL** 并发验证：mock mapper 的单测只能证明"调用了哪条 SQL"，
 * 证明不了 {@code claimed < total} 守卫在真库并发下真的成立，也证明不了
 * "唯一键失败时事务回滚会归还配额"这条设计承诺（写错守卫却全绿，正是上轮外部
 * 审查抓到的幻影测试类型）。
 * <p>
 * 本测试打本地 13306 真库；无库、或券表还没建（未重跑 init/05）时自动跳过。
 */
class CouponClaimConcurrencyRealMysqlTest {

    private static final String URL =
            "jdbc:mysql://localhost:13306/aurora_order?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "aurora123";
    private static final String TITLE_PREFIX = "concurrency-test-";

    private Connection connection;

    private static boolean couponTablesReady() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM coupon_template LIMIT 1")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        Assumptions.assumeTrue(couponTablesReady(),
                "no local mysql with coupon tables; skipping (rerun docker/mysql/init/05-aurora_order.sql)");
        connection = DriverManager.getConnection(URL, USER, PASSWORD);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection == null) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DELETE FROM user_coupon WHERE template_id IN "
                    + "(SELECT id FROM coupon_template WHERE title LIKE '" + TITLE_PREFIX + "%')");
            st.executeUpdate("DELETE FROM coupon_template WHERE title LIKE '" + TITLE_PREFIX + "%'");
        } finally {
            connection.close();
        }
    }

    @Test
    void concurrentDistinctUsersNeverOverclaim() throws Exception {
        long templateId = insertTemplate(5);
        AtomicInteger claimed = new AtomicInteger();

        runConcurrently(20, index -> {
            if (claim(templateId, 1000L + index)) {
                claimed.incrementAndGet();
            }
        });

        assertThat(claimed.get()).as("成功领取数正好等于发放总量").isEqualTo(5);
        assertThat(countCoupons(templateId)).isEqualTo(5);
        assertThat(claimedCount(templateId)).as("模板上的已领计数与券台账一致").isEqualTo(5);
    }

    @Test
    void sameUserRacingItselfGetsExactlyOneAndReturnsTheQuota() throws Exception {
        long templateId = insertTemplate(10);
        AtomicInteger claimed = new AtomicInteger();

        runConcurrently(8, index -> {
            if (claim(templateId, 2000L)) {   // 同一个 userId 并发抢 8 次
                claimed.incrementAndGet();
            }
        });

        assertThat(claimed.get()).as("同一用户并发只应成功一次").isEqualTo(1);
        assertThat(countCoupons(templateId)).isEqualTo(1);
        assertThat(claimedCount(templateId))
                .as("抢输的 7 次整体回滚、配额随之归还——只剩这 1 次占用的")
                .isEqualTo(1);
    }

    // ---------- 被测语义的裸 JDBC 复刻（与 CouponService 的两条 SQL 一一对应） ----------

    private boolean claim(long templateId, long userId) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE coupon_template SET claimed = claimed + 1 WHERE id = ? AND claimed < total")) {
                    ps.setLong(1, templateId);
                    if (ps.executeUpdate() != 1) {
                        c.rollback();
                        return false;   // 已领完
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO user_coupon (template_id, user_id, status, title, threshold_amount,"
                                + " discount_amount, claimed_at, expire_at) VALUES (?,?,?,?,?,?,?,?)")) {
                    ps.setLong(1, templateId);
                    ps.setLong(2, userId);
                    ps.setString(3, "UNUSED");
                    ps.setString(4, TITLE_PREFIX + templateId);
                    ps.setBigDecimal(5, new BigDecimal("100.00"));
                    ps.setBigDecimal(6, new BigDecimal("20.00"));
                    ps.setObject(7, LocalDateTime.now());
                    ps.setObject(8, LocalDateTime.now().plusDays(30));
                    ps.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();       // 唯一键冲突：整体回滚，配额归还
                return false;
            }
        }
    }

    private void runConcurrently(int threads, SqlTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    task.run(index);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    private interface SqlTask {
        void run(int index) throws SQLException;
    }

    private long insertTemplate(int total) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO coupon_template (title, threshold_amount, discount_amount, total, claimed,"
                        + " claim_start_at, claim_end_at, valid_days) VALUES (?,?,?,?,0,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, TITLE_PREFIX + System.nanoTime());
            ps.setBigDecimal(2, new BigDecimal("100.00"));
            ps.setBigDecimal(3, new BigDecimal("20.00"));
            ps.setInt(4, total);
            ps.setObject(5, LocalDateTime.now().minusMinutes(1));
            ps.setObject(6, LocalDateTime.now().plusDays(1));
            ps.setInt(7, 30);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private int countCoupons(long templateId) throws SQLException {
        return scalar("SELECT COUNT(*) FROM user_coupon WHERE template_id = " + templateId);
    }

    private int claimedCount(long templateId) throws SQLException {
        return scalar("SELECT claimed FROM coupon_template WHERE id = " + templateId);
    }

    private int scalar(String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
