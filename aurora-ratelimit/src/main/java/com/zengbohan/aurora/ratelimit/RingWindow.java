package com.zengbohan.aurora.ratelimit;

import java.util.Arrays;

/**
 * 环形桶时间窗：滑动窗口限流器与熔断器共享的统计基元。
 * <p>
 * 把时间轴切成 {@code buckets} 个等长槽，每个槽带 {@code dimensions} 个计数维度；
 * {@link #touch} 对当前槽做惰性过期（上一圈的桶清零），{@link #sums} 只累计
 * 「最近 buckets 个槽」内的桶——这就是滑动语义本身。调用方负责自己的并发策略
 * （两个现有用户都在外层 synchronized）。
 * <p>
 * 抽取动机（M3 评审 Standards 轴）：限流器的单计数与熔断器的三计数
 * （total/failure/slow）曾是两份同构的 floorMod/过期清零/窗口求和代码。
 */
public final class RingWindow {

    private final int buckets;
    private final long bucketMillis;
    private final int dimensions;
    // counts[槽下标][维度]；由使用方的锁保护。
    private final long[][] counts;
    // 各槽当前归属的时间槽号；槽号不匹配 = 桶已过期。
    private final long[] slots;
    // 观测到的最大槽号：用于识别时钟回拨。
    private long maxObservedSlot = Long.MIN_VALUE;

    public RingWindow(int buckets, long bucketMillis, int dimensions) {
        if (buckets <= 0 || dimensions <= 0) {
            throw new IllegalArgumentException("buckets/dimensions must be positive");
        }
        if (bucketMillis <= 0) {
            throw new IllegalArgumentException("bucketMillis must be positive: " + bucketMillis);
        }
        this.buckets = buckets;
        this.bucketMillis = bucketMillis;
        this.dimensions = dimensions;
        this.counts = new long[buckets][dimensions];
        this.slots = new long[buckets];
        Arrays.fill(slots, Long.MIN_VALUE);
    }

    // 当前时间槽下标；该槽属于上一圈时先清零（惰性过期，免去后台清理线程）。
    public int touch(long nowMillis) {
        long slot = Math.floorDiv(nowMillis, bucketMillis);
        if (slot < maxObservedSlot) {
            // 时钟回拨/NTP 校时：旧桶全部是“未来”时间戳，继续累计会污染窗口，
            // 一律整窗清空（与 SnowflakeIdGenerator 的时钟回拨守卫策略一致）
            reset();
        }
        maxObservedSlot = Math.max(maxObservedSlot, slot);
        int idx = (int) Math.floorMod(slot, buckets);
        if (slots[idx] != slot) {
            slots[idx] = slot;
            Arrays.fill(counts[idx], 0L);
        }
        return idx;
    }

    // 向当前时间槽的指定维度累加。
    public void add(long nowMillis, int dimension, long delta) {
        if (dimension < 0 || dimension >= dimensions) {
            throw new IllegalArgumentException("dimension out of range: " + dimension);
        }
        counts[touch(nowMillis)][dimension] += delta;
    }

    // 最近 buckets 个槽内各维度的合计；更老的桶自然出窗。
    public long[] sums(long nowMillis) {
        long slot = Math.floorDiv(nowMillis, bucketMillis);
        long[] totals = new long[dimensions];
        for (int i = 0; i < buckets; i++) {
            // slots[i] <= slot：回拨后被“未来”时间戳标记的桶不再计入
            if (slots[i] > slot - buckets && slots[i] <= slot) {
                for (int d = 0; d < dimensions; d++) {
                    totals[d] += counts[i][d];
                }
            }
        }
        return totals;
    }

    // 全部清零（熔断恢复 CLOSED 时重开统计）。
    public void reset() {
        for (long[] bucket : counts) {
            Arrays.fill(bucket, 0L);
        }
        Arrays.fill(slots, Long.MIN_VALUE);
        maxObservedSlot = Long.MIN_VALUE;
    }
}
