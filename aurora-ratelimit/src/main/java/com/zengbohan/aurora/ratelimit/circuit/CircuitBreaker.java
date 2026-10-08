package com.zengbohan.aurora.ratelimit.circuit;

import com.zengbohan.aurora.ratelimit.RingWindow;

import java.util.concurrent.Callable;

/**
 * 熔断器：CLOSED / OPEN / HALF_OPEN 三态机，以装饰器形式包裹任意调用。
 * <p>
 * CLOSED：用环形桶滑动窗口（{@link RingWindow}，三维：total/failure/slow）
 * 统计失败率与慢调用率，任一超阈值转 OPEN。
 * OPEN：不触达被包裹调用，直接快速失败；持续时长到达转 HALF_OPEN。
 * HALF_OPEN：放行有限次试探（并发下严格有界），全部成功回 CLOSED，任一失败回 OPEN。
 */
public class CircuitBreaker {

    private final CircuitBreakerConfig config;

    private final Object lock = new Object();
    private CircuitBreakerState state = CircuitBreakerState.CLOSED;
    // OPEN 起始时刻。
    private long openedAt;
    // HALF_OPEN 已放行的试探数。
    private int halfOpenInFlight;
    // HALF_OPEN 试探成功数。
    private int halfOpenSuccesses;
    /** HALF_OPEN 轮次号：每次进入 HALF_OPEN 递增。迟到探针的结果按轮次作废——
     *  HALF_OPEN→OPEN→HALF_OPEN 后 state 会同值，需要 generation 区分。 */
    private int halfOpenGeneration;

    // 统计窗口：维度 0=总次数、1=失败数、2=慢调用数。
    private final RingWindow window;

    public CircuitBreaker(CircuitBreakerConfig config) {
        this.config = config;
        long bucketMillis = Math.max(1, config.statWindowMillis / config.windowBuckets);
        this.window = new RingWindow(config.windowBuckets, bucketMillis, 3);
    }

    /**
     * 当前状态。注意：这是一个有副作用的读——OPEN 持续时长已到时会在本次调用里
     * 惰性迁移到 HALF_OPEN（不靠后台定时器）。并发下由内部锁保证迁移只发生一次。
     */
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
        Permission permission = acquirePermission();
        if (permission.kind() == Permission.Kind.REJECT) {
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
            recordResult(success, elapsedMillis, permission);
        }
    }

    // 不抛受检异常的便捷变体：包裹 Supplier。
    public <T> T executeSupplier(java.util.function.Supplier<T> supplier) {
        try {
            return execute(supplier::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // Supplier.get() 不声明受检异常，此分支实际不可达；防御性包装保持签名诚实
            throw new IllegalStateException("unexpected checked exception from supplier", e);
        }
    }

    // 一次调用的放行判定结果：kind + 放行时的 HALF_OPEN 轮次（仅 PROBE 有意义）。
    private record Permission(Kind kind, int generation) {
        enum Kind {
            // 熔断打开或半开试探满员：快速失败。
            REJECT,
            // CLOSED 正常放行。
            NORMAL,
            // HALF_OPEN 试探放行。
            PROBE
        }
        static final Permission REJECT = new Permission(Kind.REJECT, 0);
        static final Permission NORMAL = new Permission(Kind.NORMAL, 0);
    }

    private Permission acquirePermission() {
        synchronized (lock) {
            checkOpenTimeout();
            if (state == CircuitBreakerState.OPEN) {
                return Permission.REJECT;
            }
            if (state == CircuitBreakerState.HALF_OPEN) {
                if (halfOpenInFlight >= config.halfOpenPermittedCalls) {
                    return Permission.REJECT;
                }
                halfOpenInFlight++;
                return new Permission(Permission.Kind.PROBE, halfOpenGeneration);
            }
            return Permission.NORMAL;
        }
    }

    // OPEN 持续时长到达则转 HALF_OPEN。调用方需持锁。
    private void checkOpenTimeout() {
        if (state == CircuitBreakerState.OPEN
                && config.clock.getAsLong() - openedAt >= config.openDurationMillis) {
            transitionTo(CircuitBreakerState.HALF_OPEN);
            halfOpenInFlight = 0;
            halfOpenSuccesses = 0;
            halfOpenGeneration++;
        }
    }

    private void recordResult(boolean success, long elapsedMillis, Permission permission) {
        boolean slow = elapsedMillis >= config.slowCallDurationMillis;

        synchronized (lock) {
            boolean probe = permission.kind() == Permission.Kind.PROBE;
            if (!probe) {
                // 只有常态调用进统计窗：OPEN 期间的探针结果计入窗口会把陈旧
                // 失败率/慢调用率带进下一轮 CLOSED，互相放大
                recordIntoBucket(success, slow);
            }
            if (probe) {
                // 迟到探针：轮次或状态已推进，结果不生效，计数不回滚
                if (permission.generation() != halfOpenGeneration
                        || state != CircuitBreakerState.HALF_OPEN) {
                    return;
                }
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
                transitionTo(CircuitBreakerState.OPEN);
                openedAt = config.clock.getAsLong();
            }
        }
    }

    // 记录一次调用到当前滑动窗口桶（三维：total / failure / slow）。
    private void recordIntoBucket(boolean success, boolean slow) {
        long now = config.clock.getAsLong();
        window.add(now, 0, 1);
        if (!success) {
            window.add(now, 1, 1);
        }
        if (slow) {
            window.add(now, 2, 1);
        }
    }

    // CLOSED 下是否达到熔断阈值（失败率或慢调用率）。
    private boolean shouldOpen() {
        long[] sums = window.sums(config.clock.getAsLong());
        long total = sums[0];
        long failures = sums[1];
        long slows = sums[2];
        if (total < config.minRequestThreshold) {
            return false; // 小样本不判定
        }
        int failureRate = (int) (failures * 100 / total);
        int slowRate = (int) (slows * 100 / total);
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
        window.reset();
    }

    // 状态变更监听（为 M4 指标留缝）。
    public interface Listener {
        void onStateChange(CircuitBreakerState from, CircuitBreakerState to);
    }
}
