package com.zengbohan.aurora.ratelimit;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 滑动窗口限流器：环形桶分段计数（统计基元见 {@link RingWindow}）。
 * <p>
 * 窗口被切成 {@code bucketCount} 个等长时间槽，当前时刻落在哪个槽就计数在哪个槽；
 * 判定时只累计「最近 bucketCount 个槽」的计数，因此窗口是连续滑动的，
 * 没有固定窗口（窗口起点对齐）在临界点放行双倍配额的缺陷。
 * <p>
 * 并发策略：整个判定加锁，换取严格不超发（与 Guava RateLimiter 同级的取舍）；
 * 环形桶的价值在于把「统计与清理」都压到 O(bucketCount)，临界区极短。
 */
public class SlidingWindowRateLimiter implements RateLimiter {

    // 默认桶数：窗口切成 10 段，精度与开销的折中。
    private static final int DEFAULT_BUCKET_COUNT = 10;

    private final int limit;
    private final RingWindow window;
    private final LongSupplier clock;

    public SlidingWindowRateLimiter(int limit, Duration window) {
        this(limit, window, DEFAULT_BUCKET_COUNT, System::currentTimeMillis);
    }

    public SlidingWindowRateLimiter(int limit, Duration window, int bucketCount) {
        this(limit, window, bucketCount, System::currentTimeMillis);
    }

    // 注入时钟的构造器，供测试控制时间。
    SlidingWindowRateLimiter(int limit, Duration window, int bucketCount, LongSupplier clock) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive: " + window);
        }
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive: " + bucketCount);
        }
        long windowMillis = window.toMillis();
        if (windowMillis < bucketCount) {
            throw new IllegalArgumentException(
                    "window " + window + " is too short for " + bucketCount + " buckets");
        }
        this.limit = limit;
        this.window = new RingWindow(bucketCount, windowMillis / bucketCount, 1);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive: " + permits);
        }
        long now = clock.getAsLong();
        long inWindow = window.sums(now)[0];
        if (inWindow + permits <= limit) {
            window.add(now, 0, permits);
            return true;
        }
        return false;
    }
}
