package com.zengbohan.aurora.sharding.pilot;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分片之后"谁保证唯一"：主键与唯一键。
 * 这三条是本试点里对**本项目**影响最直接的发现（详见报告）。
 */
class ShardIdentityTest {

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
    void autoIncrementCollidesAcrossShards() throws Exception {
        // 两个用户落在两个库，各自的自增计数器互不知情
        PilotEnv.exec(PilotEnv.sharded, "INSERT INTO pilot_autoinc (user_id, note) VALUES (?, ?)", 2L, "ds0-first");
        PilotEnv.exec(PilotEnv.sharded, "INSERT INTO pilot_autoinc (user_id, note) VALUES (?, ?)", 3L, "ds1-first");

        assertThat(PilotEnv.longs(PilotEnv.raw0, "SELECT id FROM pilot_autoinc")).containsExactly(1L);
        assertThat(PilotEnv.longs(PilotEnv.raw1, "SELECT id FROM pilot_autoinc")).containsExactly(1L);
        // 跨片看（广播查询）：两行主键都是 1 —— AUTO_INCREMENT 在分片下不能当全局主键
        assertThat(PilotEnv.longs(PilotEnv.sharded, "SELECT id FROM pilot_autoinc"))
                .containsExactlyInAnyOrder(1L, 1L);
    }

    @Test
    void externalIdGeneratorKeepsOrderIdsGloballyUnique() throws Exception {
        // 与本项目现状一致：id 由集中式 aurora-id-generator 供号（这里手工给号模拟），分片不影响全局唯一
        PilotEnv.insertOrder(PilotEnv.sharded, 9001L, 2L, "10.00");
        PilotEnv.insertOrder(PilotEnv.sharded, 9002L, 3L, "10.00");

        assertThat(PilotEnv.longs(PilotEnv.sharded, "SELECT id FROM orders"))
                .containsExactlyInAnyOrder(9001L, 9002L);
    }

    @Test
    void shardingSphereSnowflakeFillsIdWhenNotSupplied() throws Exception {
        // 对照项：不依赖外部号段，让分片中间件生成（配置里 keyGenerateStrategy: snowflake）
        for (long userId : List.of(2L, 3L)) {
            PilotEnv.exec(PilotEnv.sharded,
                    "INSERT INTO orders (user_id, sku_id, quantity, total_amount, status) VALUES (?, ?, ?, ?, ?)",
                    userId, 7L, 1, new BigDecimal("10.00"), 0);
        }

        List<Long> ids = PilotEnv.longs(PilotEnv.sharded, "SELECT id FROM orders");
        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> assertThat(id)
                .as("雪花主键应为 19 位量级，而不是各片各自从 1 开始")
                .isGreaterThan(1_000_000_000_000_000_000L));
    }

    @Test
    void uniqueKeyWithoutShardKeyIsOnlyEnforcedInsideOneShard() throws Exception {
        // uk_code 上有唯一键，但它不含分片键（user_id）→ 两个库各存一份，全局唯一性不再成立
        PilotEnv.exec(PilotEnv.sharded, "INSERT INTO pilot_unique (id, user_id, uk_code) VALUES (?, ?, ?)",
                1001L, 2L, "DUP");
        PilotEnv.exec(PilotEnv.sharded, "INSERT INTO pilot_unique (id, user_id, uk_code) VALUES (?, ?, ?)",
                1002L, 3L, "DUP");

        assertThat(PilotEnv.longs(PilotEnv.raw0, "SELECT COUNT(*) FROM pilot_unique WHERE uk_code = 'DUP'"))
                .containsExactly(1L);
        assertThat(PilotEnv.longs(PilotEnv.raw1, "SELECT COUNT(*) FROM pilot_unique WHERE uk_code = 'DUP'"))
                .containsExactly(1L);

        // 同一片内仍然拦得住（分片键相同 → 路由到同一个库）
        assertThatThrownBy(() -> PilotEnv.exec(PilotEnv.sharded,
                "INSERT INTO pilot_unique (id, user_id, uk_code) VALUES (?, ?, ?)", 1003L, 2L, "DUP"))
                .isInstanceOf(SQLException.class);
    }
}
