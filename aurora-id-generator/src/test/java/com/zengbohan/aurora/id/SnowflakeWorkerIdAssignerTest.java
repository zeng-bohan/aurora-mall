package com.zengbohan.aurora.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * workerId 分配：显式配置优先且校验范围；缺省按 hostname/pid 推导，确定可复现。
 */
class SnowflakeWorkerIdAssignerTest {

    @Test
    void explicitPropertyWinsOverEnv() {
        assertThat(SnowflakeWorkerIdAssigner.assign("7", "9", "host-a", 1)).isEqualTo(7);
    }

    @Test
    void envUsedWhenPropertyAbsent() {
        assertThat(SnowflakeWorkerIdAssigner.assign(null, "9", "host-a", 1)).isEqualTo(9);
    }

    @Test
    void blankExplicitFallsBackToDerived() {
        assertThat(SnowflakeWorkerIdAssigner.assign("   ", null, "host-a", 1)).isBetween(0, 1023);
    }

    @Test
    void explicitOutOfRangeRejected() {
        assertThatThrownBy(() -> SnowflakeWorkerIdAssigner.assign("1024", null, "host-a", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[0, 1023]");
        assertThatThrownBy(() -> SnowflakeWorkerIdAssigner.assign("-1", null, "host-a", 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void explicitNonNumericRejected() {
        assertThatThrownBy(() -> SnowflakeWorkerIdAssigner.assign("abc", null, "host-a", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aurora.snowflake.worker-id");
    }

    @Test
    void derivedValueIsDeterministicAndInRange() {
        int first = SnowflakeWorkerIdAssigner.assign(null, null, "host-a", 42);
        int second = SnowflakeWorkerIdAssigner.assign(null, null, "host-a", 42);
        assertThat(first).isEqualTo(second);
        assertThat(first).isBetween(0, 1023);
    }

    @Test
    void blankHostnameStillResolves() {
        assertThat(SnowflakeWorkerIdAssigner.assign(null, null, "  ", 7)).isBetween(0, 1023);
    }
}
