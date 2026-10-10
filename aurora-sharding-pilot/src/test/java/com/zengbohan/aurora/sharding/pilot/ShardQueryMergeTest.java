package com.zengbohan.aurora.sharding.pilot;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨分片查询的归并：分页与聚合。
 * 这两处是"分片后最容易被业务发现"的地方——SQL 写法不变，但每条语句背后的代价与语义都变了。
 */
class ShardQueryMergeTest {

    @AfterAll
    static void shutdown() {
        PilotEnv.close();
    }

    // 就绪判定必须在 @BeforeEach：在 @BeforeAll 里 assume 失败会让整类静默消失
    // （surefire 记 0 tests / 0 skipped），测试总数因此不可信。
    @BeforeEach
    void prepare() throws Exception {
        Assumptions.assumeTrue(PilotEnv.mysqlReachable(), "MySQL(13306) 不可达：跳过分片试点");
        PilotEnv.boot();
        PilotEnv.reset();
    }

    @Test
    void crossShardPaginationReturnsTheGloballyCorrectPage() throws Exception {
        // 6 行交错落在两库：501/503/505 -> ds0，502/504/506 -> ds1
        for (int i = 0; i < 6; i++) {
            PilotEnv.insertOrder(PilotEnv.sharded, 501L + i, i % 2 == 0 ? 2L : 3L, "10.00");
        }

        ListAppender<ILoggingEvent> appender = PilotEnv.captureSql();
        List<Long> page2;
        try {
            page2 = PilotEnv.longs(PilotEnv.sharded, "SELECT id FROM orders ORDER BY id DESC LIMIT 2 OFFSET 2");
        } finally {
            PilotEnv.stopCapture(appender);
        }

        // 全局排序下的第 3、4 名——归并必须跨两片重新排，不能各取各的第 2 页
        assertThat(page2).containsExactly(504L, 503L);
        // 每片都被查了，且片内 SQL 也带上了 LIMIT（重写后的确切形状见测试输出与报告）
        PilotEnv.assertRoutedTo(PilotEnv.actualSql(appender), "ds0", "ds1");
        assertThat(PilotEnv.actualSql(appender)).allSatisfy(sql -> assertThat(sql).contains("LIMIT"));
    }

    @Test
    void crossShardAggregationMergesPerShardResults() throws Exception {
        PilotEnv.insertOrder(PilotEnv.sharded, 601L, 2L, "10.00");   // ds0
        PilotEnv.insertOrder(PilotEnv.sharded, 602L, 2L, "20.00");   // ds0
        PilotEnv.insertOrder(PilotEnv.sharded, 603L, 3L, "30.00");   // ds1

        ListAppender<ILoggingEvent> appender = PilotEnv.captureSql();
        long count;
        java.math.BigDecimal sum;
        try {
            count = PilotEnv.longs(PilotEnv.sharded, "SELECT COUNT(*) FROM orders").get(0);
            sum = PilotEnv.decimal(PilotEnv.sharded, "SELECT SUM(total_amount) FROM orders");
        } finally {
            PilotEnv.stopCapture(appender);
        }

        // 逻辑层：两片的结果被归并
        assertThat(count).isEqualTo(3L);
        assertThat(sum).isEqualByComparingTo("60.00");
        PilotEnv.assertRoutedTo(PilotEnv.actualSql(appender), "ds0", "ds1");

        // 物理层对账：两片各自的行数与金额之和，加起来必须等于上面的逻辑结果
        long rows0 = PilotEnv.longs(PilotEnv.raw0, "SELECT COUNT(*) FROM orders").get(0);
        long rows1 = PilotEnv.longs(PilotEnv.raw1, "SELECT COUNT(*) FROM orders").get(0);
        assertThat(rows0 + rows1).isEqualTo(count);
        assertThat(PilotEnv.decimal(PilotEnv.raw0, "SELECT SUM(total_amount) FROM orders"))
                .isEqualByComparingTo("30.00");
        assertThat(PilotEnv.decimal(PilotEnv.raw1, "SELECT SUM(total_amount) FROM orders"))
                .isEqualByComparingTo("30.00");
    }
}
