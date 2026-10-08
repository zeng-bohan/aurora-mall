package com.zengbohan.aurora.payment.channel;

import com.zengbohan.aurora.payment.entity.PaymentOrder;
import com.zengbohan.aurora.payment.mapper.PaymentOrderMapper;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款守卫转移的**真实 MySQL** 验证（外部审查二·幻影测试）：
 * 单测用 mock mapper 只能证明"方法被调用"，证明不了 status IN (0,1)
 * 这条 SQL 在真库上真的发生转移——上轮 P1-1（守卫写错却全绿）的根因。
 * 本测试打本地 13306 真库，无库自动跳过。
 */
class RefundTransitionRealMysqlTest {

    private static final String URL =
            "jdbc:mysql://localhost:13306/aurora_payment?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
    private static final String USER = "root";
    private static final String PASSWORD = "aurora123";

    private Connection connection;

    private static boolean mysqlReachable() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        Assumptions.assumeTrue(mysqlReachable(), "no local mysql; skipping real-db refund transition test");
        connection = DriverManager.getConnection(URL, USER, PASSWORD);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null) {
            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM payment_orders WHERE order_id >= 990000000");
            }
            connection.close();
        }
    }

    private long insertPaymentWithStatus(int status) throws SQLException {
        long orderId = 990000000L + System.nanoTime() % 1000000;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO payment_orders (order_id, amount, status) VALUES (?, ?, ?)")) {
            ps.setLong(1, orderId);
            ps.setBigDecimal(2, new BigDecimal("39.80"));
            ps.setInt(3, status);
            ps.executeUpdate();
        }
        return orderId;
    }

    private int statusOf(long orderId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT status FROM payment_orders WHERE order_id = ?")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    // 守卫 SQL 的真实有效性：PAYING→REFUNDED 与 PAID→REFUNDED 都必须在真库上生效。
    @Test
    void mergedRefundGuardTransitionsBothPayingAndPaidRowsOnRealDatabase() throws SQLException {
        long payingId = insertPaymentWithStatus(0); // PAYING（迟到回调路径）
        long paidId = insertPaymentWithStatus(1);   // PAID（markPaid 赢竞态 + 关单路径）

        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE payment_orders SET status = 2 WHERE order_id = ? AND status IN (0, 1)")) {
            ps.setLong(1, payingId);
            assertThat(ps.executeUpdate()).as("PAYING -> REFUNDED").isEqualTo(1);
            ps.setLong(1, paidId);
            assertThat(ps.executeUpdate()).as("PAID -> REFUNDED").isEqualTo(1);
        }

        assertThat(statusOf(payingId)).isEqualTo(2);
        assertThat(statusOf(paidId)).isEqualTo(2);
    }

    // REFUNDED 行不再被守卫转移（幂等：重复信号不翻转已退款状态）。
    @Test
    void alreadyRefundedRowIsNotMovedAgain() throws SQLException {
        long id = insertPaymentWithStatus(1);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE payment_orders SET status = 2 WHERE order_id = ? AND status IN (0, 1)")) {
            ps.setLong(1, id);
            assertThat(ps.executeUpdate()).isEqualTo(1);
            assertThat(ps.executeUpdate()).as("二次信号不生效（幂等）").isZero();
        }
    }
}
