package com.zengbohan.aurora.product.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

// 逻辑过期信封：redis key 的存活时间长于 expireAt，是否过期在每次读取时判断。
@JsonIgnoreProperties(ignoreUnknown = true)
public class CacheWrapper<V> {

    private V data;
    private Instant expireAt;

    public CacheWrapper() {
    }

    public CacheWrapper(V data, Instant expireAt) {
        this.data = data;
        this.expireAt = expireAt;
    }

    public V getData() {
        return data;
    }

    public Instant getExpireAt() {
        return expireAt;
    }
}
