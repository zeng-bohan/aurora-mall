package com.zengbohan.aurora.ratelimit;

import java.util.function.LongSupplier;

/**
 * 令牌桶限流器：恒定速率补充令牌，桶容量决定可容忍的突发量。
 * <p>
 * 采用惰性补充：不跑定时任务，取令牌时按「距上次补充的逝去时间 × 速率」一次性补足，
 * 上限为桶容量。令牌是小数（double）累计，短于 1 个令牌的时间不会白白丢失。
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final double ratePerSecond;
    private final double capacity;
    private final LongSupplier nanoClock;

    // 当前令牌数，由本对象锁保护。
    private double tokens;
    private long lastRefillNanos;

    public TokenBucketRateLimiter(double ratePerSecond, double capacity) {
        this(ratePerSecond, capacity, System::nanoTime);
    }

    // 注入时钟的构造器，供测试控制时间。
    TokenBucketRateLimiter(double ratePerSecond, double capacity, LongSupplier nanoClock) {
        if (!Double.isFinite(ratePerSecond) || ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be positive: " + ratePerSecond);
        }
        if (!Double.isFinite(capacity) || capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.ratePerSecond = ratePerSecond;
        this.capacity = capacity;
        this.nanoClock = nanoClock;
        this.tokens = capacity; // 初始满桶：允许冷启动瞬间的突发
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    @Override
    public synchronized boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive: " + permits);
        }
        if (permits > capacity) {
            // 单次请求超过桶容量，永远不可能满足
            return false;
        }
        refill();
        if (tokens >= permits) {
            tokens -= permits;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed / 1_000_000_000.0 * ratePerSecond);
            lastRefillNanos = now;
        }
    }
}
