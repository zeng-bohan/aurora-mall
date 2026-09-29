package com.zengbohan.aurora.gateway.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 Redis 的分布式限流：并发限额精确、窗口滑动后配额恢复。
 * 无 Redis 自动跳过（同 StockLuaIntegrationTest 模式），CI 无 Redis 保持绿。
 * Run locally: docker compose up -d redis
 */
class RedisSlidingWindowRateLimiterIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "16379"));

    private LettuceConnectionFactory factory;
    private RedisSlidingWindowRateLimiter limiter;
    private String key;

    private static boolean redisReachable() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(HOST, PORT), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(redisReachable(),
                "no redis at " + HOST + ":" + PORT + "; skipping redis rate limit integration test");
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(HOST, PORT));
        factory.afterPropertiesSet();
        ReactiveStringRedisTemplate template = new ReactiveStringRedisTemplate(factory);
        limiter = new RedisSlidingWindowRateLimiter(template);
        key = "aurora:test:rl:" + System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        if (factory != null) {
            new ReactiveStringRedisTemplate(factory).delete(key).block();
            factory.destroy();
        }
    }

    @Test
    void enforcesLimitExactlyWithinWindow() {
        // 限额 5：前 5 个放行，之后持续拒绝（窗口未滑动）
        List<Boolean> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 7; i++) {
            results.add(limiter.tryAcquire(key, 5, Duration.ofSeconds(10)).block());
        }
        assertThat(results.subList(0, 5)).containsExactly(true, true, true, true, true);
        assertThat(results.subList(5, 7)).containsExactly(false, false);
    }

    @Test
    void windowSlideRestoresQuota() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(key, 5, Duration.ofMillis(800)).block()).isTrue();
        }
        assertThat(limiter.tryAcquire(key, 5, Duration.ofMillis(800)).block()).isFalse();

        Thread.sleep(900); // 滑动窗口（0.8s）滑过，成员被 ZREMRANGEBYSCORE 清掉
        assertThat(limiter.tryAcquire(key, 5, Duration.ofMillis(800)).block())
                .as("窗口滑动后配额恢复").isTrue();
    }

    @Test
    void concurrentCallersShareTheGlobalQuota() throws Exception {
        // 多实例共享配额：20 线程 × 5 次（共 100 次尝试）抢 50 配额，恰好 50 个放行
        int threads = 20;
        int attemptsPerThread = 5;
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int j = 0; j < attemptsPerThread; j++) {
                        if (Boolean.TRUE.equals(limiter.tryAcquire(key, 50, Duration.ofSeconds(30)).block())) {
                            allowed.incrementAndGet();
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

        assertThat(allowed.get()).as("共享配额精确不超发").isEqualTo(50);
        assertThat(rejected.get()).isEqualTo(threads * attemptsPerThread - 50);
    }
}
