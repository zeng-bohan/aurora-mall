package com.zengbohan.aurora.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 滑动窗口限流器：注入可控时钟，把窗口边界与滑动行为测成确定性的。
 */
class SlidingWindowRateLimiterTest {

    /** 测试用假时钟（毫秒）。 */
    private long now;

    private SlidingWindowRateLimiter limiter(int limit, Duration window, int buckets) {
        return new SlidingWindowRateLimiter(limit, window, buckets, () -> now);
    }

    @Test
    void allowsUpToLimitThenRejectsWithinSameWindow() {
        SlidingWindowRateLimiter limiter = limiter(5, Duration.ofSeconds(1), 10);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();
        // 时间未推进，配额不会自行恢复
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void windowFullySlidesFreesOldQuota() {
        SlidingWindowRateLimiter limiter = limiter(5, Duration.ofSeconds(1), 10);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire();
        }

        // 边界前 1ms：最早的桶仍在窗口内
        now = 999;
        assertThat(limiter.tryAcquire()).isFalse();

        // 边界时刻：最早那个桶出窗，配额整体恢复
        now = 1000;
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    void partialSlideOnlyFreesExpiredBuckets() {
        SlidingWindowRateLimiter limiter = limiter(10, Duration.ofSeconds(1), 10);

        now = 0;
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire();
        }
        now = 150; // 第二个桶
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire();
        }
        assertThat(limiter.tryAcquire()).isFalse();

        // t=1000：只有 t=0 的桶出窗，释放 5 个配额
        now = 1000;
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
        assertThat(limiter.tryAcquire()).isFalse();

        // t=1300：t=150 的桶也出窗，配额再次释放
        now = 1300;
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    void permitsWeighAgainstLimit() {
        SlidingWindowRateLimiter limiter = limiter(10, Duration.ofSeconds(1), 10);

        assertThat(limiter.tryAcquire(7)).isTrue();
        assertThat(limiter.tryAcquire(4)).isFalse();
        assertThat(limiter.tryAcquire(3)).isTrue();
        assertThat(limiter.tryAcquire(1)).isFalse();
    }

    @Test
    void rejectsInvalidConfigurationAndArguments() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter(0, Duration.ofSeconds(1), 10));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter(10, Duration.ZERO, 10));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter(10, Duration.ofMillis(-1), 10));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter(10, Duration.ofSeconds(1), 0));
        // 窗口短于桶数：桶粒度会被压到 0，直接拒绝而不是静默劣化
        assertThatIllegalArgumentException()
                .isThrownBy(() -> limiter(10, Duration.ofMillis(5), 10));

        SlidingWindowRateLimiter limiter = limiter(10, Duration.ofSeconds(1), 10);
        assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire(0));
        assertThatIllegalArgumentException().isThrownBy(() -> limiter.tryAcquire(-1));
    }

    @Test
    void concurrentAcquisitionsNeverExceedLimit() throws Exception {
        // 窗口开得足够长，测试期间不发生滑动，断言可以严格相等
        SlidingWindowRateLimiter limiter = limiter(50, Duration.ofSeconds(60), 10);

        int threads = 20;
        int attemptsPerThread = 10; // 共 200 次尝试抢 50 个配额
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
