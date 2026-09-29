package com.zengbohan.aurora.common.idempotent;

/** Redis-backed request guard. Returns true when the key was acquired first. */
public interface RedisIdempotentStore {

    boolean tryAcquire(String key, long ttlSeconds);

    /** Releases a guard acquired by a call that then failed, so retries pass. */
    void release(String key);
}
