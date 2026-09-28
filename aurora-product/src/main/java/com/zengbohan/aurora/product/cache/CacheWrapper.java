package com.zengbohan.aurora.product.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** Logical-expiry envelope: the redis key outlives expireAt, staleness is judged per read. */
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
