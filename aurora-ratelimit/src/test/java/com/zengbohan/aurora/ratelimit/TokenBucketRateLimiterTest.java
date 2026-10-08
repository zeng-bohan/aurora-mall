package com.zengbohan.aurora.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 令牌桶限流器：注入纳秒假时钟，把补充速率与突发上限测成确定性的。
 */
class TokenBucketRateLimiterTest {

    // 测试用假时钟（纳秒）。
    private long nanos;

    private TokenBucketRateLimiter limiter(double ratePerSecond, double capacity) {
        return new TokenBucketRateLimiter(ratePerSecond, capacity, () -> nanos);
    }

    private void advanceMillis(long millis) {
        nanos += millis * 1_000_000L;
    }

    @Test
    void burstUpToCapacityThenRejects() {
        TokenBucketRateLimiter limiter = limiter(1, 10);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void refillsAtConfiguredRate() {
        TokenBucketRateLimiter limiter = limiter(2, 10);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire();
        }

        advanceMillis(2000); // 2s × 2/s = 4 个令牌
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void refillIsCappedAtCapacity() {
        TokenBucketRateLimiter limiter = limiter(2, 10);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire();
        }

        advanceMillis(100_000); // 远超容量，只补到 10
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void fractionalRefillAccumulatesPrecisely() {
        TokenBucketRateLimiter limiter = limiter(2, 1);
        assertThat(limiter.tryAcquire()).isTrue();

        advanceMillis(250); // 0.5 个令牌，不够 1 个
        assertThat(limiter.tryAcquire()).isFalse();

        advanceMillis(250); // 累计 1.0 个令牌，小数零头没有被丢弃
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    void singleRequestLargerThanCapacityIsAlwaysRejected() {
        TokenBucketRateLimiter limiter = limiter(1, 10);

        assertThat(limiter.tryAcquire(11)).isFalse();
        // 被拒绝的请求不消耗令牌
        assertThat(limiter.tryAcquire(10)).isTrue();
    }

    @Test
    void rejectsInvalidConfigurationAndArguments() {
        assertThatIllegalArgumentException().isThrownBy(() -> limiter(0, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> limiter(-1, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> limiter(Double.NaN, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> limiter(1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> limiter(1, Double.POSITIVE_INFINITY));

        TokenBucketRateLimiter limiter = limiter(1, 10);
        assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire(0));
        assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire(-1));
    }

    @Test
    void concurrentAcquisitionsNeverExceedCapacity() throws Exception {
        // 时钟固定，无补充，断言可以严格相等
        TokenBucketRateLimiter limiter = limiter(0.001, 50);

        int threads = 20;
        int attemptsPerThread = 10; // 共 200 次尝试抢 50 个令牌
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int j = 0; j < attemptsPerThread; j++) {
                        if (limiter.tryAcquire()) {
                            succeeded.incrementAndGet();
                        } else {
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(succeeded.get()).isEqualTo(50); // 一个不多
        assertThat(rejected.get()).isEqualTo(150);
    }
}
