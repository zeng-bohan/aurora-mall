package com.zengbohan.aurora.common.idempotent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The template is resolved lazily through an ObjectProvider: the bean always
 * registers (component scan runs before auto-configuration, so
 * @ConditionalOnBean would never see the template), and a context without
 * redis fails only when a REDIS-guarded call actually runs.
 */
@Component
public class RedisIdempotentStoreImpl implements RedisIdempotentStore {

    static final String KEY_PREFIX = "aurora:idempotent:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public RedisIdempotentStoreImpl(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    @Override
    public boolean tryAcquire(String key, long ttlSeconds) {
        return Boolean.TRUE.equals(template().opsForValue()
                .setIfAbsent(KEY_PREFIX + key, "1", Duration.ofSeconds(ttlSeconds)));
    }

    @Override
    public void release(String key) {
        template().delete(KEY_PREFIX + key);
    }

    private StringRedisTemplate template() {
        StringRedisTemplate template = redisProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException(
                    "@Idempotent(REDIS) needs a StringRedisTemplate bean; add spring-boot-starter-data-redis and its config");
        }
        return template;
    }
}
