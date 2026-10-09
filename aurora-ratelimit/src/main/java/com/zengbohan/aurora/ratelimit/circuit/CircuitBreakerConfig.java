package com.zengbohan.aurora.ratelimit.circuit;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * 熔断器配置。用 Builder 构造，阈值/窗口/试探数全部可配。
 */
public final class CircuitBreakerConfig {

    // 失败率阈值（百分比，0-100），窗口内达到即熔断。
    final int failureRateThreshold;
    // 慢调用率阈值（百分比，0-100）。
    final int slowCallRateThreshold;
    // 慢调用判定阈值：单次耗时超过此值算慢调用。
    final long slowCallDurationMillis;
    // 最小请求数：窗口内请求数低于此值不判定（避免小样本抖动误熔断）。
    final int minRequestThreshold;
    // 半开状态放行的试探数。
    final int halfOpenPermittedCalls;
    // OPEN 持续时长，到期转 HALF_OPEN。
    final long openDurationMillis;
    // 统计窗口时长：失败率/慢调用率的观察窗，与 OPEN 持续时长互相独立。
    final long statWindowMillis;
    // 统计窗口桶数。
    final int windowBuckets;
    final LongSupplier clock;
    final CircuitBreaker.Listener listener;
    // 不计入失败率的异常（如两端配置不一致这类"重试无意义"的错误）：
    // 异常照样抛给调用方，只是不推进熔断统计。
    final Predicate<Throwable> ignoredFailures;

    private CircuitBreakerConfig(Builder builder) {
        this.failureRateThreshold = builder.failureRateThreshold;
        this.slowCallRateThreshold = builder.slowCallRateThreshold;
        this.slowCallDurationMillis = builder.slowCallDurationMillis;
        this.minRequestThreshold = builder.minRequestThreshold;
        this.halfOpenPermittedCalls = builder.halfOpenPermittedCalls;
        this.openDurationMillis = builder.openDurationMillis;
        this.statWindowMillis = builder.statWindowMillis;
        this.windowBuckets = builder.windowBuckets;
        this.clock = builder.clock;
        this.listener = builder.listener;
        this.ignoredFailures = builder.ignoredFailures;
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
        private long statWindowMillis = 10_000;
        private int windowBuckets = 10;
        private LongSupplier clock = System::currentTimeMillis;
        private CircuitBreaker.Listener listener = (from, to) -> {
        };
        private Predicate<Throwable> ignoredFailures = ignored -> false;

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

        public Builder openDuration(Duration duration) {
            return openDurationMillis(duration.toMillis());
        }

        public Builder statWindowMillis(long millis) {
            if (millis <= 0) {
                throw new IllegalArgumentException("statWindowMillis must be positive: " + millis);
            }
            this.statWindowMillis = millis;
            return this;
        }

        public Builder statWindow(Duration duration) {
            return statWindowMillis(duration.toMillis());
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

        /**
         * 指定"不计入失败率"的异常（判定为 true 的异常既不进失败数也不进总样本数）：
         * 异常仍原样抛给调用方，只是不参与熔断判定——用于"重试无意义、也不代表
         * 对端不健康"的错误（如两端配置不一致）。默认全部计入。
         */
        public Builder ignoreFailures(Predicate<Throwable> ignored) {
            this.ignoredFailures = ignored == null ? ignoredFailure -> false : ignored;
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
