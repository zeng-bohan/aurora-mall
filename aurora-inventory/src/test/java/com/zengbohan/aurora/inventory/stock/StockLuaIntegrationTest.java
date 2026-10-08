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
 * 库存 Lua 脚本对着真实的 redis 执行（本机开发环境是 localhost:16379）。
 * 无 redis 可达时自动跳过，因此没有 redis 的 CI runner 依然保持绿色——
 * 单元测试 mock 了 execute()，只能证明映射关系，证明不了本测试存在的意义
 * 所在的脚本语义：并发扣减不会超卖、回滚能精确还原。
 *
 * 本地运行：docker compose up -d redis
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
