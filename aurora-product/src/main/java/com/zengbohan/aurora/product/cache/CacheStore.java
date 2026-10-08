package com.zengbohan.aurora.product.cache;

import java.time.Duration;

/**
 * 最小化的 JSON 序列化缓存抽象，让缓存模式无需真实 redis
 * 也能做单元测试。
 */
public interface CacheStore {

    String get(String key);

    void put(String key, String value, Duration ttl);

    void delete(String key);

    boolean setIfAbsent(String key, String value, Duration ttl);
}
