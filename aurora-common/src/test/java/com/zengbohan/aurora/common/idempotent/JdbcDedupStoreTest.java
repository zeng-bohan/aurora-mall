package com.zengbohan.aurora.common.idempotent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 惰性清理：插入前按小时节流清一次过期行、保留期取自配置；清理失败不影响守卫。
 * <p>
 * 注意 {@code JdbcTemplate.update} 是重载 + varargs：匹配器必须能定型（typing），
 * 且 Mockito 对 varargs 按**元素**比对，所以这里用 {@code anyString()} / {@code eq(...)}
 * 而不是数组匹配器。
 */
class JdbcDedupStoreTest {

    private static final String TABLE = "idempotent_record";
    private static final long RETENTION_SECONDS = 3600L;

    private JdbcTemplate jdbc;
    private JdbcDedupStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        store = new JdbcDedupStore(provider, TABLE, RETENTION_SECONDS);
    }

    @Test
    void purgesExpiredRowsOncePerWindowAndKeepsInserting() {
        assertThat(store.tryInsert("stock-sync", "m1")).isTrue();
        assertThat(store.tryInsert("stock-sync", "m2")).isTrue();

        verify(jdbc, times(2)).update(
                argThat((String sql) -> sql.startsWith("INSERT INTO")), anyString(), anyString());
        // 同一小时窗口内只清一次：第二次插入不再触发 DELETE，且保留期取自配置而非写死
        verify(jdbc, times(1)).update(
                argThat((String sql) -> sql.startsWith("DELETE FROM") && sql.contains("created_at <")),
                eq(RETENTION_SECONDS));
    }

    @Test
    void purgeFailureDoesNotBreakTheGuard() {
        when(jdbc.update(argThat((String sql) -> sql.startsWith("DELETE FROM")), eq(RETENTION_SECONDS)))
                .thenThrow(new RuntimeException("lock wait timeout exceeded"));

        assertThat(store.tryInsert("stock-sync", "m1")).isTrue();
    }

    @Test
    void duplicateInsertReportsNotFirstTime() {
        when(jdbc.update(argThat((String sql) -> sql.startsWith("INSERT INTO")), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("uk_idempotent"));

        assertThat(store.tryInsert("stock-sync", "m1")).isFalse();
    }
}
