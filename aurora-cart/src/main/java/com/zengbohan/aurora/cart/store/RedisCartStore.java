package com.zengbohan.aurora.cart.store;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Redis hash per user: key aurora:cart:{userId}, field skuId, value quantity. */
@Component
public class RedisCartStore implements CartStore {

    static final String KEY_PREFIX = "aurora:cart:";

    private final StringRedisTemplate redis;

    public RedisCartStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(long userId, long skuId, long delta) {
        Object result = redis.opsForHash().increment(key(userId), String.valueOf(skuId), delta);
        return ((Number) result).longValue();
    }

    @Override
    public void put(long userId, long skuId, long quantity) {
        redis.opsForHash().put(key(userId), String.valueOf(skuId), String.valueOf(quantity));
    }

    @Override
    public void remove(long userId, long skuId) {
        redis.opsForHash().delete(key(userId), String.valueOf(skuId));
    }

    @Override
    public void clear(long userId) {
        redis.delete(key(userId));
    }

    @Override
    public Map<Long, Long> entries(long userId) {
        Map<Object, Object> raw = redis.opsForHash().entries(key(userId));
        Map<Long, Long> lines = new LinkedHashMap<>();
        raw.forEach((field, value) -> lines.put(Long.valueOf(field.toString()),
                Long.valueOf(value.toString())));
        return lines;
    }

    private String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
