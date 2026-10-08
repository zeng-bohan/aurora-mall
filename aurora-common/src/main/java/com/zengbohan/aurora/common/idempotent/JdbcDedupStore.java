package com.zengbohan.aurora.common.idempotent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 与 redis store 相同的原因，惰性解析 JdbcTemplate：
 * 该 Bean 总是注册，没有数据源的上下文只在实际使用时才失败。
 */
@Component
public class JdbcDedupStore implements DedupStore {

    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final String table;

    public JdbcDedupStore(ObjectProvider<JdbcTemplate> jdbcProvider,
                          @Value("${aurora.idempotent.dedup-table:idempotent_record}") String table) {
        this.jdbcProvider = jdbcProvider;
        this.table = table;
    }

    @Override
    public void remove(String bizType, String bizKey) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            throw new IllegalStateException(
                    "@Idempotent(DB_DEDUP) needs a JdbcTemplate bean; add spring-jdbc and a datasource");
        }
        jdbc.update("DELETE FROM " + table + " WHERE biz_type = ? AND biz_key = ?", bizType, bizKey);
    }

    @Override
    public boolean tryInsert(String bizType, String bizKey) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            throw new IllegalStateException(
                    "@Idempotent(DB_DEDUP) needs a JdbcTemplate bean; add spring-jdbc and a datasource");
        }
        try {
            jdbc.update("INSERT INTO " + table + " (biz_type, biz_key) VALUES (?, ?)", bizType, bizKey);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean exists(String bizType, String bizKey) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            throw new IllegalStateException(
                    "DedupStore needs a JdbcTemplate bean; add spring-jdbc and a datasource");
        }
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE biz_type = ? AND biz_key = ?",
                Integer.class, bizType, bizKey);
        return n != null && n > 0;
    }
}
