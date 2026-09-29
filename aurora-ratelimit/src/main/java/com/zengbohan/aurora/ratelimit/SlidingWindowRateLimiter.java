package com.zengbohan.aurora.ratelimit;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 滑动窗口限流器：环形桶分段计数。
 * <p>
 * 窗口被切成 {@code bucketCount} 个等长时间槽，当前时刻落在哪个槽就计数在哪个槽；
 * 判定时只累计「最近 bucketCount 个槽」的计数，因此窗口是连续滑动的，
 * 没有固定窗口（窗口起点对齐）在临界点放行双倍配额的缺陷。
 * <p>
 * 并发策略：整个判定加锁，换取严格不超发（与 Guava RateLimiter 同级的取舍）；
 * 环形桶的价值在于把「统计与清理」都压到 O(bucketCount)，临界区极短。
 */
public class SlidingWindowRateLimiter implements RateLimiter {

    /** 默认桶数：窗口切成 10 段，精度与开销的折中。 */
    private static final int DEFAULT_BUCKET_COUNT = 10;

    private final int limit;
    private final int bucketCount;
    private final long bucketMillis;
    private final LongSupplier clock;

    /** 环形桶计数，下标 = 槽号 % bucketCount，由本对象锁保护。 */
    private final long[] counts;
    /** 各桶当前归属的时间槽号；槽号不匹配 = 桶已过期，下次使用前清零。 */
    private final long[] slotStarts;

    public SlidingWindowRateLimiter(int limit, Duration window) {
        this(limit, window, DEFAULT_BUCKET_COUNT, System::currentTimeMillis);
    }

    public SlidingWindowRateLimiter(int limit, Duration window, int bucketCount) {
        this(limit, window, bucketCount, System::currentTimeMillis);
    }

    /** 注入时钟的构造器，供测试控制时间。 */
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
        this.bucketCount = bucketCount;
        this.bucketMillis = windowMillis / bucketCount;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.counts = new long[bucketCount];
        this.slotStarts = new long[bucketCount];
        Arrays.fill(slotStarts, Long.MIN_VALUE);
    }

    @Override
    public synchronized boolean tryAcquire(int permits) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be positive: " + permits);
        }
        long slot = clock.getAsLong() / bucketMillis;
        int idx = (int) Math.floorMod(slot, bucketCount);
        if (slotStarts[idx] != slot) {
            // 环形复用：这个桶属于上一圈，清零后归当前槽
            slotStarts[idx] = slot;
            counts[idx] = 0;
        }
        long inWindow = 0;
        for (int i = 0; i < bucketCount; i++) {
            // 只统计最近 bucketCount 个槽内的桶，更老的自然出窗
            if (slotStarts[i] > slot - bucketCount) {
                inWindow += counts[i];
            }
        }
        if (inWindow + permits <= limit) {
            counts[idx] += permits;
            return true;
        }
        return false;
    }
}
