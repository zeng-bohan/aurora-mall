package com.zengbohan.aurora.common.idempotent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;

/**
 * 模板通过 ObjectProvider 惰性解析：该 Bean 总是注册
 * （组件扫描先于自动配置运行，因此 @ConditionalOnBean
 * 永远看不到模板），而没有 redis 的上下文
 * 只会在真正执行 REDIS 守卫的调用时才失败。
 */
@Component
public class RedisIdempotentStoreImpl implements RedisIdempotentStore {

    static final String KEY_PREFIX = "aurora:idempotent:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public RedisIdempotentStoreImpl(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    private static final RedisScript<Long> RELEASE_IF_MATCHES = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Override
    public String tryAcquire(String key, long ttlSeconds) {
        String token = UUID.randomUUID().toString();
        boolean acquired = Boolean.TRUE.equals(template().opsForValue()
                .setIfAbsent(KEY_PREFIX + key, token, Duration.ofSeconds(ttlSeconds)));
        return acquired ? token : null;
    }

    @Override
    public void release(String key, String token) {
        template().execute(RELEASE_IF_MATCHES,
                Collections.singletonList(KEY_PREFIX + key), token);
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
