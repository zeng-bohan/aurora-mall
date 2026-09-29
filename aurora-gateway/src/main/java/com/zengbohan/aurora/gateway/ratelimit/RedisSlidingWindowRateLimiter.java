package com.zengbohan.aurora.gateway.ratelimit;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Redis 分布式滑动窗口限流器：ZSET + 单 Lua 脚本原子完成
 * 「清过期成员 → 计数 → 达标拒绝 / 写入成员 → 刷 TTL」。
 * <p>
 * key 维度（路由）共享配额——网关多实例打同一个 key，配额是全局的。
 * ZSET 成员用 UUID 保证同毫秒并发写入不互相覆盖；score = 毫秒时间戳，
 * 滑动清理靠 ZREMRANGEBYSCORE 在每次判定前先摘掉窗口外的成员。
 */
@Component
public class RedisSlidingWindowRateLimiter {

    /** 返回 1 = 放行，0 = 限流。 */
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

    private final ReactiveStringRedisTemplate redis;

    public RedisSlidingWindowRateLimiter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 尝试占用一个配额；true = 放行。全程响应式，不阻塞事件循环。 */
    public Mono<Boolean> tryAcquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();
        String member = UUID.randomUUID().toString();
        return redis.execute(SCRIPT, List.of(key),
                        Long.toString(now), Long.toString(windowMillis),
                        Integer.toString(limit), member)
                .next()
                .map(result -> result == 1L)
                .defaultIfEmpty(false);
    }
}
