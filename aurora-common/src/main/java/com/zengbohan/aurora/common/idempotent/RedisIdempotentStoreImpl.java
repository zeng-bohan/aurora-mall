package com.zengbohan.aurora.common.idempotent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisIdempotentStoreImpl implements RedisIdempotentStore {

    static final String KEY_PREFIX = "aurora:idempotent:";

    private final StringRedisTemplate redis;

    public RedisIdempotentStoreImpl(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String key, long ttlSeconds) {
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(KEY_PREFIX + key, "1", Duration.ofSeconds(ttlSeconds)));
    }
}
