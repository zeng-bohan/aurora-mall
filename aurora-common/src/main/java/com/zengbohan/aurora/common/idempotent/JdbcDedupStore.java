package com.zengbohan.aurora.common.idempotent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 与 redis store 相同的原因，惰性解析 JdbcTemplate：
 * 该 Bean 总是注册，没有数据源的上下文只在实际使用时才失败。
 * <p>
 * 惰性清理：DB_DEDUP 成功后行会保留（只有调用失败才删），不清理会随调用量无限增长。
 * 这里不发定时任务——插入前按小时级节流顺带清一次超过保留期的旧行；清理失败只记日志，
 * 绝不影响守卫本身（与 TokenBucket 的"惰性补充"同一思路，也不需要服务方开 @EnableScheduling）。
 */
@Component
public class JdbcDedupStore implements DedupStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcDedupStore.class);

    // 惰性清理的节流窗口：整个 JVM 每小时最多清一次。
    static final long PURGE_INTERVAL_MILLIS = 3600_000L;
    // 旧行保留期默认 7 天：必须大于 MQ 重投递的最长窗口（RocketMQ 默认重试 16 次、约 4.6 小时），
    // 否则清早了会让重投递的消息被当成新消息再处理一次。
    static final long DEFAULT_RETENTION_SECONDS = 7L * 24 * 60 * 60;

    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final String table;
    private final long retentionSeconds;
    private final AtomicLong nextPurgeAt = new AtomicLong();

    public JdbcDedupStore(ObjectProvider<JdbcTemplate> jdbcProvider,
                          @Value("${aurora.idempotent.dedup-table:idempotent_record}") String table,
                          @Value("${aurora.idempotent.retention-seconds:" + DEFAULT_RETENTION_SECONDS + "}")
                          long retentionSeconds) {
        this.jdbcProvider = jdbcProvider;
        this.table = table;
        this.retentionSeconds = retentionSeconds;
    }

    @Override
    public void remove(String bizType, String bizKey) {
        requireJdbc().update("DELETE FROM " + table + " WHERE biz_type = ? AND biz_key = ?", bizType, bizKey);
    }

    @Override
    public boolean tryInsert(String bizType, String bizKey) {
        JdbcTemplate jdbc = requireJdbc();
        purgeIfDue(jdbc);
        try {
            jdbc.update("INSERT INTO " + table + " (biz_type, biz_key) VALUES (?, ?)", bizType, bizKey);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean exists(String bizType, String bizKey) {
        Integer n = requireJdbc().queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE biz_type = ? AND biz_key = ?",
                Integer.class, bizType, bizKey);
        return n != null && n > 0;
    }

    private JdbcTemplate requireJdbc() {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            throw new IllegalStateException(
                    "@Idempotent(DB_DEDUP) needs a JdbcTemplate bean; add spring-jdbc and a datasource");
        }
        return jdbc;
    }

    // 节流：CAS 抢到本窗口的线程执行一次清理，其余线程直接跳过（热路径只多一次原子读）。
    private void purgeIfDue(JdbcTemplate jdbc) {
        long now = System.currentTimeMillis();
        long slot = nextPurgeAt.get();
        if (now < slot || !nextPurgeAt.compareAndSet(slot, now + PURGE_INTERVAL_MILLIS)) {
            return;
        }
        try {
            int deleted = jdbc.update("DELETE FROM " + table
                    + " WHERE created_at < DATE_SUB(NOW(), INTERVAL ? SECOND)", retentionSeconds);
            if (deleted > 0) {
                log.info("purged {} expired row(s) from {}", deleted, table);
            }
        } catch (RuntimeException e) {
            // 清理是顺带做的：失败不能让守卫失效（下一小时窗口再试；与调用方事务同源，
            // 事务回滚时清理也一并回滚，无正确性影响）
            log.warn("purge of {} failed, will retry in the next window: {}", table, e.toString());
        }
    }
}
