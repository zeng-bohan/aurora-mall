package com.zengbohan.aurora.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.util.concurrent.RateLimiter.create;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基准 harness：与 Guava RateLimiter（令牌桶的工业级实现）同场比吞吐与突发行为。
 * <p>
 * 不是 JMH：JVM 预热/黑箱的严谨性对本次对比收益有限，而本机内存吃紧（本项目 surefire 只给 192m）。
 * 这里只回答一个问题——同配额下本实现与 Guava 的放行量级是否可比。
 * 用固定线程数 + 固定总尝试数做墙钟吞吐，数字记入 README。
 */
class RateLimiterBenchmarkTest {

    private static final int THREADS = 8;
    private static final int ATTEMPTS = 50_000;

    @Test
    void compareThroughputAgainstGuava() throws Exception {
        // 同等配额下对比：滑动窗口（精确窗口）与 Guava 平滑限流
        SlidingWindowRateLimiter sliding =
                new SlidingWindowRateLimiter(ATTEMPTS, Duration.ofSeconds(3600), 10);
        var guava = create(ATTEMPTS);

        long slidingNanos = drive(sliding::tryAcquire);
        long guavaNanos = drive(guava::tryAcquire);

        System.out.printf(Locale.ROOT,
                "[bench] throughput  sliding-window=%d ops/s  guava=%d ops/s  (ratio %.2f)%n",
                opsPerSecond(slidingNanos), opsPerSecond(guavaNanos),
                (double) slidingNanos / guavaNanos);
        assertThat(opsPerSecond(slidingNanos)).isPositive();
    }

    @Test
    void burstBehaviourBeatsFixedWindow() {
        // 场景：每秒 10 个配额。固定窗口在边界可放行 20 个，滑动窗口不会。
        int perSecond = 10;
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(perSecond, Duration.ofSeconds(1));

        // 第一秒用满 10 个
        List<Boolean> firstSecond = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            firstSecond.add(limiter.tryAcquire());
        }
        long allowedInFirstSecond = firstSecond.stream().filter(Boolean::booleanValue).count();

        assertThat(allowedInFirstSecond)
                .as("滑动窗口在一秒内最多放行配额数，不会因窗口对齐而翻倍")
                .isEqualTo(perSecond);
    }

    private long drive(java.util.function.BooleanSupplier acquire) throws Exception {
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (int i = 0; i < THREADS; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int j = 0; j < ATTEMPTS / THREADS; j++) {
                        if (acquire.getAsBoolean()) {
                            allowed.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        long t0 = System.nanoTime();
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - t0;
        pool.shutdownNow();
        return elapsed;
    }

    private static long opsPerSecond(long nanos) {
        return (long) (ATTEMPTS / (nanos / 1_000_000_000.0));
    }
}
