package com.zengbohan.aurora.common.idempotent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Lazily resolves JdbcTemplate for the same reason as the redis store: the
 * bean always registers, contexts without a datasource fail only on use.
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
}
