package com.zengbohan.aurora.sharding.pilot;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由：分片键等值只打一片；无分片键广播；改分片键的后果。
 * 断言分两层——Actual SQL 证明"打到哪一片"，直连物理库证明"数据真在哪一片"。
 */
class ShardRoutingTest {

    @AfterAll
    static void shutdown() {
        PilotEnv.close();
    }

    // 就绪判定必须在 @BeforeEach：在 @BeforeAll 里 assume 失败会让整类静默消失
    // （surefire 记 0 tests / 0 skipped），测试总数因此不可信。
    @BeforeEach
    void prepare() throws Exception {
        Assumptions.assumeTrue(PilotEnv.mysqlReachable(),
                "MySQL(13306) 不可达：跳过分片试点（与 Redis/Lua 集成测试同法）");
        PilotEnv.boot();
        PilotEnv.reset();
    }

    @Test
    void preciseLookupTouchesExactlyOneShard() throws Exception {
        PilotEnv.insertOrder(PilotEnv.sharded, 101L, 2L, "10.00");   // user 2 -> ds0
        PilotEnv.insertOrder(PilotEnv.sharded, 102L, 3L, "20.00");   // user 3 -> ds1

        ListAppender<ILoggingEvent> appender = PilotEnv.captureSql();
        List<Long> ids;
        try {
            ids = PilotEnv.longs(PilotEnv.sharded, "SELECT id FROM orders WHERE user_id = ?", 2L);
        } finally {
            PilotEnv.stopCapture(appender);
        }

        assertThat(ids).containsExactly(101L);
        PilotEnv.assertRoutedTo(PilotEnv.actualSql(appender), "ds0");
    }

    @Test
    void rowsReallyLiveInTheirOwnSchema() throws Exception {
        // 走分片连接写入，再从两个物理库直连取证：数据真的是分到两个库，而不是只在逻辑层看着像
        PilotEnv.insertOrder(PilotEnv.sharded, 201L, 2L, "10.00");
        PilotEnv.insertOrder(PilotEnv.sharded, 202L, 3L, "10.00");

        assertThat(PilotEnv.longs(PilotEnv.raw0, "SELECT id FROM orders WHERE user_id = 2")).containsExactly(201L);
        assertThat(PilotEnv.longs(PilotEnv.raw0, "SELECT id FROM orders WHERE user_id = 3")).isEmpty();
        assertThat(PilotEnv.longs(PilotEnv.raw1, "SELECT id FROM orders WHERE user_id = 3")).containsExactly(202L);
        assertThat(PilotEnv.longs(PilotEnv.raw1, "SELECT id FROM orders WHERE user_id = 2")).isEmpty();
    }

    @Test
    void queryWithoutShardKeyBroadcastsToBothShards() throws Exception {
        PilotEnv.insertOrder(PilotEnv.sharded, 301L, 2L, "10.00");
        PilotEnv.insertOrder(PilotEnv.sharded, 302L, 3L, "20.00");

        ListAppender<ILoggingEvent> appender = PilotEnv.captureSql();
        List<Long> ids;
        try {
            // 无分片键：按状态查订单（线上"查我的待支付"以外的后台查询就是这个形状）
            ids = PilotEnv.longs(PilotEnv.sharded,
                    "SELECT id FROM orders WHERE status = ?", 0);
        } finally {
            PilotEnv.stopCapture(appender);
        }

        PilotEnv.assertRoutedTo(PilotEnv.actualSql(appender), "ds0", "ds1");
        assertThat(ids).containsExactlyInAnyOrder(301L, 302L);
    }

    @Test
    void updatingTheShardKeyIsRejectedOutright() throws Exception {
        // 边界：改分片键。本来担心的是"静默错位"（按旧值路由、新值与分片不再一致），
        // 实测是更干净的结果——中间件直接拒绝：Can not update sharding value for table 'orders'。
        PilotEnv.insertOrder(PilotEnv.sharded, 401L, 2L, "10.00");

        assertThatThrownBy(() -> PilotEnv.exec(PilotEnv.sharded,
                "UPDATE orders SET user_id = ? WHERE id = ?", 3L, 401L))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Can not update sharding value");

        // 数据没被动过：行仍在 ds0，分片键仍是 2，按 2 查得到、按 3 查不到
        assertThat(PilotEnv.longs(PilotEnv.raw0, "SELECT id FROM orders WHERE id = 401")).containsExactly(401L);
        assertThat(PilotEnv.longs(PilotEnv.raw1, "SELECT id FROM orders WHERE id = 401")).isEmpty();
        assertThat(PilotEnv.longs(PilotEnv.sharded, "SELECT id FROM orders WHERE user_id = ?", 2L))
                .containsExactly(401L);
        assertThat(PilotEnv.longs(PilotEnv.sharded, "SELECT id FROM orders WHERE user_id = ?", 3L)).isEmpty();
    }
}
