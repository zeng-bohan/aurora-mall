package com.zengbohan.aurora.product.cache;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// 内存版 CacheStore：统计读取次数，并可冻结"物理"过期。
public class FakeCacheStore implements CacheStore {

    private final Map<String, String> data = new ConcurrentHashMap<>();
    public final AtomicInteger dbLoads = new AtomicInteger();

    @Override
    public String get(String key) {
        return data.get(key);
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        data.put(key, value);
    }

    @Override
    public void delete(String key) {
        data.remove(key);
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return data.putIfAbsent(key, value) == null;
    }

    public String peek(String key) {
        return data.get(key);
    }

    public boolean has(String key) {
        return data.containsKey(key);
    }
}
