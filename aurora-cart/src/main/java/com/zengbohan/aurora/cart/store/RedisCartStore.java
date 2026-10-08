package com.zengbohan.aurora.cart.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每个用户一个 Redis Hash：key = aurora:cart:{userId}，field = skuId，value = quantity。
 * <p>
 * 所有写入路径都会刷新 TTL，实现购物车的滑动过期。
 */
@Component
public class RedisCartStore implements CartStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCartStore.class);

    private static final String KEY_PREFIX = "aurora:cart:";

    // 购物车滑动过期时间。
    private static final Duration TTL = Duration.ofDays(30);

    /**
     * 原子地执行「增量 + 下限保护 + 刷新 TTL」。
     * <p>
     * 若增量后数量 &lt;= 0，则删除该字段并返回 0，避免出现负数量或 0 数量的脏数据。
     */
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            """
            local key   = KEYS[1]
            local field = ARGV[1]
            local delta = tonumber(ARGV[2])
            local ttl   = tonumber(ARGV[3])

            local newVal = redis.call('HINCRBY', key, field, delta)
            if newVal <= 0 then
                redis.call('HDEL', key, field)
                newVal = 0
            end
            if ttl > 0 then
                redis.call('EXPIRE', key, ttl)
            end
            return newVal
            """,
            Long.class);

    private final StringRedisTemplate redis;

    public RedisCartStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(long userId, long skuId, long delta) {
        Long result = redis.execute(
                INCREMENT_SCRIPT,
                List.of(key(userId)),
                field(skuId),
                String.valueOf(delta),
                String.valueOf(TTL.getSeconds()));
        return result == null ? 0L : result;
    }

    @Override
    public void put(long userId, long skuId, long quantity) {
        String key = key(userId);
        if (quantity <= 0) {
            // 与 increment 的语义保持一致：数量归零即移除该行。
            redis.opsForHash().delete(key, field(skuId));
        } else {
            redis.opsForHash().put(key, field(skuId), String.valueOf(quantity));
        }
        redis.expire(key, TTL);
    }

    @Override
    public void remove(long userId, long skuId) {
        redis.opsForHash().delete(key(userId), field(skuId));
    }

    @Override
    public void clear(long userId) {
        redis.delete(key(userId));
    }

    @Override
    public Map<Long, Long> entries(long userId) {
        Map<Object, Object> raw = redis.opsForHash().entries(key(userId));
        Map<Long, Long> lines = new LinkedHashMap<>(raw.size());
        raw.forEach((field, value) -> {
            try {
                long skuId = Long.parseLong(field.toString());
                long quantity = Long.parseLong(value.toString());
                if (quantity > 0) {
                    lines.put(skuId, quantity);
                }
            } catch (NumberFormatException ex) {
                log.warn("忽略购物车中的非法字段: userId={}, field={}, value={}",
                        userId, field, value, ex);
            }
        });
        return lines;
    }

    private static String key(long userId) {
        return KEY_PREFIX + userId;
    }

    private static String field(long skuId) {
        return Long.toString(skuId);
    }
}
