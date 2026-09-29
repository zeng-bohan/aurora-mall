package com.zengbohan.aurora.ratelimit.circuit;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 熔断器配置。用 Builder 构造，阈值/窗口/试探数全部可配。
 */
public final class CircuitBreakerConfig {

    /** 失败率阈值（百分比，0-100），窗口内达到即熔断。 */
    final int failureRateThreshold;
    /** 慢调用率阈值（百分比，0-100）。 */
    final int slowCallRateThreshold;
    /** 慢调用判定阈值：单次耗时超过此值算慢调用。 */
    final long slowCallDurationMillis;
    /** 最小请求数：窗口内请求数低于此值不判定（避免小样本抖动误熔断）。 */
    final int minRequestThreshold;
    /** 半开状态放行的试探数。 */
    final int halfOpenPermittedCalls;
    /** OPEN 持续时长，到期转 HALF_OPEN。 */
    final long openDurationMillis;
    /** 统计窗口桶数。 */
    final int windowBuckets;
    final LongSupplier clock;
    final CircuitBreaker.Listener listener;

    private CircuitBreakerConfig(Builder builder) {
        this.failureRateThreshold = builder.failureRateThreshold;
        this.slowCallRateThreshold = builder.slowCallRateThreshold;
        this.slowCallDurationMillis = builder.slowCallDurationMillis;
        this.minRequestThreshold = builder.minRequestThreshold;
        this.halfOpenPermittedCalls = builder.halfOpenPermittedCalls;
        this.openDurationMillis = builder.openDurationMillis;
        this.windowBuckets = builder.windowBuckets;
        this.clock = builder.clock;
        this.listener = builder.listener;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private int failureRateThreshold = 50;
        private int slowCallRateThreshold = 60;
        private long slowCallDurationMillis = 1000;
        private int minRequestThreshold = 10;
        private int halfOpenPermittedCalls = 3;
        private long openDurationMillis = 10_000;
        private int windowBuckets = 10;
        private LongSupplier clock = System::currentTimeMillis;
        private CircuitBreaker.Listener listener = (from, to) -> {
        };

        public Builder failureRateThreshold(int percent) {
            this.failureRateThreshold = requireRange(percent, "failureRateThreshold");
            return this;
        }

        public Builder slowCallRateThreshold(int percent) {
            this.slowCallRateThreshold = requireRange(percent, "slowCallRateThreshold");
            return this;
        }

        public Builder slowCallDurationMillis(long millis) {
            if (millis <= 0) {
                throw new IllegalArgumentException("slowCallDurationMillis must be positive: " + millis);
            }
            this.slowCallDurationMillis = millis;
            return this;
        }

        public Builder slowCallDuration(Duration duration) {
            return slowCallDurationMillis(duration.toMillis());
        }

        public Builder minRequestThreshold(int count) {
            if (count <= 0) {
                throw new IllegalArgumentException("minRequestThreshold must be positive: " + count);
            }
            this.minRequestThreshold = count;
            return this;
        }

        public Builder halfOpenPermittedCalls(int count) {
            if (count <= 0) {
                throw new IllegalArgumentException("halfOpenPermittedCalls must be positive: " + count);
            }
            this.halfOpenPermittedCalls = count;
            return this;
        }

        public Builder openDurationMillis(long millis) {
            if (millis <= 0) {
                throw new IllegalArgumentException("openDurationMillis must be positive: " + millis);
            }
            this.openDurationMillis = millis;
            return this;
        }

        public Builder windowBuckets(int count) {
            if (count <= 0) {
                throw new IllegalArgumentException("windowBuckets must be positive: " + count);
            }
            this.windowBuckets = count;
            return this;
        }

        public Builder clock(LongSupplier clock) {
            this.clock = clock;
            return this;
        }

        public Builder eventListener(CircuitBreaker.Listener listener) {
            this.listener = listener;
            return this;
        }

        private static int requireRange(int percent, String name) {
            if (percent <= 0 || percent > 100) {
                throw new IllegalArgumentException(name + " must be in (0,100]: " + percent);
            }
            return percent;
        }

        public CircuitBreakerConfig build() {
            return new CircuitBreakerConfig(this);
        }
    }
}
