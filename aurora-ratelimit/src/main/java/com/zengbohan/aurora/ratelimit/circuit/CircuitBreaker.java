package com.zengbohan.aurora.ratelimit.circuit;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 熔断器：CLOSED / OPEN / HALF_OPEN 三态机，以装饰器形式包裹任意调用。
 * <p>
 * CLOSED：用环形桶滑动窗口统计失败率与慢调用率，任一超阈值转 OPEN。
 * OPEN：不触达被包裹调用，直接快速失败；持续时长到达转 HALF_OPEN。
 * HALF_OPEN：放行有限次试探（并发下严格有界），全部成功回 CLOSED，任一失败回 OPEN。
 */
public class CircuitBreaker {

    private final CircuitBreakerConfig config;
    private final long bucketMillis;

    private final Object lock = new Object();
    private CircuitBreakerState state = CircuitBreakerState.CLOSED;
    /** OPEN 起始时刻。 */
    private long openedAt;
    /** HALF_OPEN 已放行的试探数。 */
    private int halfOpenInFlight;
    /** HALF_OPEN 试探成功数。 */
    private int halfOpenSuccesses;

    // 环形桶统计窗口：每桶记录 total/failure/slow
    private final int[] bucketTotal;
    private final int[] bucketFailure;
    private final int[] bucketSlow;
    private final long[] bucketSlot;
    /** 最近一次调用起始时刻（用于慢调用判定单调）。 */
    private long lastRefillBucketMillis;

    public CircuitBreaker(CircuitBreakerConfig config) {
        this.config = config;
        this.bucketMillis = Math.max(1, config.openDurationMillis / config.windowBuckets);
        this.bucketTotal = new int[config.windowBuckets];
        this.bucketFailure = new int[config.windowBuckets];
        this.bucketSlow = new int[config.windowBuckets];
        this.bucketSlot = new long[config.windowBuckets];
        Arrays.fill(bucketSlot, Long.MIN_VALUE);
        this.lastRefillBucketMillis = config.clock.getAsLong();
    }

    public CircuitBreakerState state() {
        synchronized (lock) {
            checkOpenTimeout();
            return state;
        }
    }

    /**
     * 包裹一次调用：熔断判定 + 执行 + 统计。异常原样穿透（业务异常不该被熔断吞掉）。
     */
    public <T> T execute(Callable<T> call) throws Exception {
        int permission = acquirePermission();
        if (permission == CLOSED_PROBE_REJECT) {
            throw new CircuitOpenException("circuit breaker is OPEN, call fast-failed");
        }
        long startNanos = System.nanoTime();
        boolean success = false;
        try {
            T result = call.call();
            success = true;
            return result;
        } finally {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
            recordResult(success, elapsedMillis, permission == HALF_OPEN_PROBE);
        }
    }

    private static final int CLOSED_PROBE_REJECT = -1;
    private static final int CLOSED_PROBE = 0;
    private static final int HALF_OPEN_PROBE = 1;

    /** 判定本次调用是否放行；返回 REJECT/CLOSED/HALF_OPEN 标记。 */
    private int acquirePermission() {
        synchronized (lock) {
            checkOpenTimeout();
            if (state == CircuitBreakerState.OPEN) {
                return CLOSED_PROBE_REJECT;
            }
            if (state == CircuitBreakerState.HALF_OPEN) {
                if (halfOpenInFlight >= config.halfOpenPermittedCalls) {
                    return CLOSED_PROBE_REJECT;
                }
                halfOpenInFlight++;
                return HALF_OPEN_PROBE;
            }
            return CLOSED_PROBE;
        }
    }

    /** OPEN 持续时长到达则转 HALF_OPEN。调用方需持锁。 */
    private void checkOpenTimeout() {
        if (state == CircuitBreakerState.OPEN
                && config.clock.getAsLong() - openedAt >= config.openDurationMillis) {
            transitionTo(CircuitBreakerState.HALF_OPEN);
            halfOpenInFlight = 0;
            halfOpenSuccesses = 0;
        }
    }

    private void recordResult(boolean success, long elapsedMillis, boolean halfOpenProbe) {
        boolean slow = elapsedMillis >= config.slowCallDurationMillis;
        CircuitBreakerState trigger = null;

        synchronized (lock) {
            recordIntoBucket(success, slow);
            if (halfOpenProbe) {
                halfOpenInFlight--;
                if (!success) {
                    transitionTo(CircuitBreakerState.OPEN);
                    openedAt = config.clock.getAsLong();
                    return;
                }
                halfOpenSuccesses++;
                if (halfOpenSuccesses >= config.halfOpenPermittedCalls) {
                    transitionTo(CircuitBreakerState.CLOSED);
                    resetWindow();
                }
                return;
            }
            if (state == CircuitBreakerState.CLOSED && shouldOpen()) {
                trigger = CircuitBreakerState.OPEN;
                transitionTo(CircuitBreakerState.OPEN);
                openedAt = config.clock.getAsLong();
            }
        }
    }

    /** 记录一次调用到当前滑动窗口桶。 */
    private void recordIntoBucket(boolean success, boolean slow) {
        long now = config.clock.getAsLong();
        long slot = now / bucketMillis;
        int idx = (int) Math.floorMod(slot, config.windowBuckets);
        if (bucketSlot[idx] != slot) {
            bucketSlot[idx] = slot;
            bucketTotal[idx] = 0;
            bucketFailure[idx] = 0;
            bucketSlow[idx] = 0;
        }
        bucketTotal[idx]++;
        if (!success) {
            bucketFailure[idx]++;
        }
        if (slow) {
            bucketSlow[idx]++;
        }
    }

    /** CLOSED 下是否达到熔断阈值（失败率或慢调用率）。 */
    private boolean shouldOpen() {
        int total = 0;
        int failures = 0;
        int slows = 0;
        long currentSlot = config.clock.getAsLong() / bucketMillis;
        for (int i = 0; i < config.windowBuckets; i++) {
            if (bucketSlot[i] > currentSlot - config.windowBuckets) {
                total += bucketTotal[i];
                failures += bucketFailure[i];
                slows += bucketSlow[i];
            }
        }
        if (total < config.minRequestThreshold) {
            return false; // 小样本不判定
        }
        int failureRate = (int) (failures * 100L / total);
        int slowRate = (int) (slows * 100L / total);
        return failureRate >= config.failureRateThreshold
                || slowRate >= config.slowCallRateThreshold;
    }

    private void transitionTo(CircuitBreakerState next) {
        if (state != next) {
            CircuitBreakerState from = state;
            state = next;
            config.listener.onStateChange(from, next);
        }
    }

    private void resetWindow() {
        Arrays.fill(bucketTotal, 0);
        Arrays.fill(bucketFailure, 0);
        Arrays.fill(bucketSlow, 0);
        Arrays.fill(bucketSlot, Long.MIN_VALUE);
    }

    /** 状态变更监听（为 M4 指标留缝）。 */
    public interface Listener {
        void onStateChange(CircuitBreakerState from, CircuitBreakerState to);
    }
}
