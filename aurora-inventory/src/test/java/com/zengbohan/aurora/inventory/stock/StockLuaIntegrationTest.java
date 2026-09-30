package com.zengbohan.aurora.inventory.stock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stock Lua scripts executed against a REAL redis (localhost:16379 on this
 * dev box). Skipped automatically when no redis is reachable, so CI runners
 * without one stay green - the unit tests mock execute() and can only prove
 * the mapping, not the script semantics this test exists for: concurrent
 * decrement cannot oversell and rollback restores exactly.
 *
 * Run locally: docker compose up -d redis
 */
class StockLuaIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "16379"));

    private StringRedisTemplate redis;
    private StockLuaScripts scripts;
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
                "no redis at " + HOST + ":" + PORT + "; skipping lua integration test");
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(HOST, PORT));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        scripts = new StockLuaScripts();
        key = "aurora:test:stock:" + System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            redis.delete(key);
        }
    }

    private Long reserve(String quantity) {
        return redis.execute(scripts.reserve, List.of(key), quantity);
    }

    @Test
    void reserveDecrementsExactlyAndRejectsShortfallAndMissingKey() {
        assertThat(reserve("1")).isEqualTo(-1L); // key missing

        redis.opsForValue().set(key, "10");
        assertThat(reserve("3")).isEqualTo(7L);
        assertThat(reserve("7")).isEqualTo(0L);      // exact fit allowed
        assertThat(reserve("1")).isEqualTo(-2L);     // no negative stock
        assertThat(redis.opsForValue().get(key)).isEqualTo("0");
    }

    @Test
    void rollbackRestoresExactlyOncePerOrder() {
        redis.opsForValue().set(key, "10");
        String marker = "aurora:test:released:" + System.nanoTime();

        assertThat(reserve("4")).isEqualTo(6L);
        // 首次：执行回滚，恢复 10
        assertThat(redis.execute(scripts.rollback, List.of(marker, key), "4",
                StockLuaScripts.RELEASE_MARKER_TTL_SECONDS)).isEqualTo(1L);
        assertThat(redis.opsForValue().get(key)).isEqualTo("10");
        // 同一订单重入：标记挡住，库存不再多加
        assertThat(redis.execute(scripts.rollback, List.of(marker, key), "4",
                StockLuaScripts.RELEASE_MARKER_TTL_SECONDS)).isEqualTo(0L);
        assertThat(redis.opsForValue().get(key)).isEqualTo("10");
        redis.delete(marker);
    }

    @Test
    void concurrentReservationsCannotOversell() throws Exception {
        redis.opsForValue().set(key, "50");

        int threads = 20;
        int attemptsPerThread = 5; // 100 attempts x 1 unit against stock 50
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
                        Long result = reserve("1");
                        if (result != null && result >= 0) {
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

        assertThat(succeeded.get()).isEqualTo(50);          // not one more
        assertThat(rejected.get()).isEqualTo(50);
        assertThat(redis.opsForValue().get(key)).isEqualTo("0"); // never negative
    }
}
