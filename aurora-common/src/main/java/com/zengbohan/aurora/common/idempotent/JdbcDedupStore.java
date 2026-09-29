package com.zengbohan.aurora.common.idempotent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(JdbcTemplate.class)
public class JdbcDedupStore implements DedupStore {

    private final JdbcTemplate jdbc;
    private final String table;

    public JdbcDedupStore(JdbcTemplate jdbc,
                          @org.springframework.beans.factory.annotation.Value("${aurora.idempotent.dedup-table:idempotent_record}") String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    @Override
    public boolean tryInsert(String bizType, String bizKey) {
        try {
            jdbc.update("INSERT INTO " + table + " (biz_type, biz_key) VALUES (?, ?)", bizType, bizKey);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
