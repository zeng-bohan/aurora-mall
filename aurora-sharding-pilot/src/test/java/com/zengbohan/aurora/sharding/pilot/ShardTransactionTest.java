package com.zengbohan.aurora.sharding.pilot;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事务边界，这是分片落地最硬的一关。
 *
 * <p>试点用的是 ShardingSphere 默认的**本地事务**：一个逻辑事务里如果写到了两个物理库，
 * 那就是两个各自独立的本地事务，库之间没有原子提交协议。下面三条分别钉住
 * "回滚能覆盖两库"、"跨库写在一条事务里确实跨了两个库"、"跨库回滚也被传播"，
 * 而"两个库之间没有原子提交"这件事只能靠配置 XA/Seata 解决——那是落地前提，不是测试能证明的。
 */
class ShardTransactionTest {

    @BeforeAll
    static void boot() throws Exception {
        Assumptions.assumeTrue(PilotEnv.mysqlReachable(), "MySQL(13306) 不可达：跳过分片试点");
        PilotEnv.boot();
    }

    @AfterAll
    static void shutdown() {
        PilotEnv.close();
    }

    @BeforeEach
    void clean() throws Exception {
        PilotEnv.reset();
    }

    @Test
    void sameShardTransactionRollsBackLocally() throws Exception {
        try (Connection connection = PilotEnv.sharded.getConnection()) {
            connection.setAutoCommit(false);
            PilotEnv.insertOrder(connection, 701L, 2L, 7L, "10.00");
            PilotEnv.insertOrder(connection, 702L, 2L, 7L, "10.00");
            connection.rollback();
        }

        assertThat(PilotEnv.longs(PilotEnv.sharded, "SELECT id FROM orders")).isEmpty();
        assertThat(PilotEnv.longs(PilotEnv.raw0, "SELECT id FROM orders")).isEmpty();
        assertThat(PilotEnv.longs(PilotEnv.raw1, "SELECT id FROM orders")).isEmpty();
    }

    @Test
    void oneTransactionWritingTwoUsersSpansTwoPhysicalShards() throws Exception {
        ListAppender<ILoggingEvent> appender = PilotEnv.captureSql();
        try (Connection connection = PilotEnv.sharded.getConnection()) {
            connection.setAutoCommit(false);
            PilotEnv.insertOrder(connection, 801L, 2L, 7L, "10.00");   // ds0
            PilotEnv.insertOrder(connection, 802L, 3L, 7L, "10.00");   // ds1
            connection.commit();
        } finally {
            PilotEnv.stopCapture(appender);
        }

        // 证据：同一条逻辑事务里，两个物理库都出现了 Actual SQL
        PilotEnv.assertRoutedTo(PilotEnv.actualSql(appender), "ds0", "ds1");
        assertThat(PilotEnv.longs(PilotEnv.sharded, "SELECT id FROM orders"))
                .containsExactlyInAnyOrder(801L, 802L);
        assertThat(PilotEnv.longs(PilotEnv.raw0, "SELECT id FROM orders")).containsExactly(801L);
        assertThat(PilotEnv.longs(PilotEnv.raw1, "SELECT id FROM orders")).containsExactly(802L);
    }

    @Test
    void rollbackIsPropagatedToBothShards() throws Exception {
        try (Connection connection = PilotEnv.sharded.getConnection()) {
            connection.setAutoCommit(false);
            PilotEnv.insertOrder(connection, 901L, 2L, 7L, "10.00");
            PilotEnv.insertOrder(connection, 902L, 3L, 7L, "10.00");
            connection.rollback();
        }

        // 好消息：回滚会被发给所有参与连接，两片都没留数据
        assertThat(PilotEnv.longs(PilotEnv.raw0, "SELECT id FROM orders")).isEmpty();
        assertThat(PilotEnv.longs(PilotEnv.raw1, "SELECT id FROM orders")).isEmpty();
    }
}
