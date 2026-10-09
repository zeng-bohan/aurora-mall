package com.zengbohan.aurora.seckill.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 活动维度的 Redis 分布式滑动窗口限流器。
 * <p>
 * 与网关的路由级限流是同一套算法（ZSET + 单 Lua 原子完成「清过期成员 → 计数 →
 * 达标拒绝 / 写成员 → 刷 TTL」，成员用 UUID 保证同毫秒并发写入不互相覆盖），
 * 那边是 Reactive 版（事件循环里不能阻塞），这里是 servlet 侧的阻塞版。语义刻意
 * 保持一致：多实例共享配额（同一个活动打同一个 key）、失败开放（Redis 故障放行并
 * 记 WARN——限流器故障不能放大成业务不可用，与网关过滤器同一口径）。
 */
@Component
public class SeckillRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SeckillRateLimiter.class);

    // 返回 1 = 放行，0 = 限流（与网关脚本逐句对应，便于两处对照阅读）。
    private static final RedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local key    = KEYS[1]
            local now    = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit  = tonumber(ARGV[3])
            local member = ARGV[4]

            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            local count = redis.call('ZCARD', key)
            if count >= limit then
                return 0
            end
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, window)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public SeckillRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试占用一个配额。
     *
     * @return true = 放行；false = 触发限流。Redis 不可用或结果为空时放行（fail-open）
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        try {
            Long result = redis.execute(SCRIPT, List.of(key),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(window.toMillis()),
                    String.valueOf(limit),
                    UUID.randomUUID().toString());
            return result == null || result == 1L;
        } catch (RuntimeException e) {
            log.warn("rate limiter degraded (redis unavailable), allowing request on {}: {}", key, e.toString());
            return true;
        }
    }

    /** 限流维度：活动——一个爆款活动被限流不影响其他活动照常抢购。 */
    public static String activityKey(long activityId) {
        return "seckill:rl:" + activityId;
    }
}
