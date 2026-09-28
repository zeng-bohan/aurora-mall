package com.zengbohan.aurora.product.cache;

import java.time.Duration;

/**
 * Minimal json-serializing cache abstraction so the caching patterns are
 * unit-testable without a live redis.
 */
public interface CacheStore {

    String get(String key);

    void put(String key, String value, Duration ttl);

    void delete(String key);

    boolean setIfAbsent(String key, String value, Duration ttl);
}
