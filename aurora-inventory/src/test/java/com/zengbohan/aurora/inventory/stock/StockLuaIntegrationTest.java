package com.zengbohan.aurora.inventory.stock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
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
 * 所在的脚本语义：并发扣减不会超卖、回滚能精确还原、同一订单重复预扣只扣一次。
 *
 * 本地运行：docker compose up -d redis
 */
class StockLuaIntegrationTest {

    private static final String HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "16379"));

    private StringRedisTemplate redis;
    private StockLuaScripts scripts;
    private String key;
    /** 本测试创建过的预扣守卫 key，用于 tearDown 清理（守卫 TTL 是 7 天）。 */
    private final List<String> guards = new ArrayList<>();
    private final AtomicInteger guardSeq = new AtomicInteger();

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
            guards.forEach(redis::delete);
        }
    }

    /** 每次调用用一个新的订单守卫（等价于「另一个订单」）。 */
    private Long reserve(String quantity) {
        String guard = key + ":guard:" + guardSeq.incrementAndGet();
        guards.add(guard);
        return redis.execute(scripts.reserve, List.of(key, guard), quantity,
                StockLuaScripts.RESERVE_GUARD_TTL_SECONDS);
    }

    @Test
    void reserveDecrementsExactlyAndRejectsShortfallAndMissingKey() {
        assertThat(reserve("1")).isEqualTo(-1L); // key 不存在

        redis.opsForValue().set(key, "10");
        assertThat(reserve("3")).isEqualTo(7L);
        assertThat(reserve("7")).isEqualTo(0L);      // 刚好装得下，允许
        assertThat(reserve("1")).isEqualTo(-2L);     // 不允许负库存
        assertThat(redis.opsForValue().get(key)).isEqualTo("0");
    }

    @Test
    void replayedReserveForSameOrderDeductsOnlyOnce() {
        redis.opsForValue().set(key, "10");
        String guard = key + ":replay";
        guards.add(guard);

        assertThat(redis.execute(scripts.reserve, List.of(key, guard), "3",
                StockLuaScripts.RESERVE_GUARD_TTL_SECONDS)).isEqualTo(7L);
        // 同一订单重放：守卫挡住，库存不再变化，也不报失败
        assertThat(redis.execute(scripts.reserve, List.of(key, guard), "3",
                StockLuaScripts.RESERVE_GUARD_TTL_SECONDS)).isEqualTo(-3L);
        assertThat(redis.opsForValue().get(key)).isEqualTo("7");
    }

    @Test
    void failedReserveReleasesGuardSoLegitimateRetryProceeds() {
        redis.opsForValue().set(key, "1");
        String guard = key + ":retry";
        guards.add(guard);

        // 库存不足：本次没有扣减，守卫必须被回滚
        assertThat(redis.execute(scripts.reserve, List.of(key, guard), "5",
                StockLuaScripts.RESERVE_GUARD_TTL_SECONDS)).isEqualTo(-2L);
        // 补货后同一订单重试：守卫已释放，扣减正常发生
        redis.opsForValue().set(key, "9");
        assertThat(redis.execute(scripts.reserve, List.of(key, guard), "5",
                StockLuaScripts.RESERVE_GUARD_TTL_SECONDS)).isEqualTo(4L);
        assertThat(redis.opsForValue().get(key)).isEqualTo("4");
    }

    @Test
    void compensateReserveIsNoOpWhenTheOrderNeverReserved() {
        redis.opsForValue().set(key, "10");
        String guard = key + ":never";
        guards.add(guard);
        String marker = key + ":released:never";

        // 该订单从未预扣过（守卫不存在）：补偿必须什么都不做——凭空加库存正是超卖的源头
        assertThat(redis.execute(scripts.compensateReserve, List.of(guard, key, marker), "3",
                StockLuaScripts.RELEASE_MARKER_TTL_SECONDS)).isEqualTo(0L);
        assertThat(redis.opsForValue().get(key)).isEqualTo("10");
        redis.delete(marker);
    }

    @Test
    void compensateReserveRestoresExactlyOnceWhenTheOrderDidReserve() {
        redis.opsForValue().set(key, "10");
        String guard = key + ":unknown";
        guards.add(guard);
        String marker = key + ":released:unknown";

        // 预扣确实落地（守卫存在），但调用方没拿到响应
        assertThat(redis.execute(scripts.reserve, List.of(key, guard), "4",
                StockLuaScripts.RESERVE_GUARD_TTL_SECONDS)).isEqualTo(6L);
        assertThat(redis.execute(scripts.compensateReserve, List.of(guard, key, marker), "4",
                StockLuaScripts.RELEASE_MARKER_TTL_SECONDS)).isEqualTo(1L);
        assertThat(redis.opsForValue().get(key)).isEqualTo("10");
        // 重复补偿：标记挡住，不会再加一次
        assertThat(redis.execute(scripts.compensateReserve, List.of(guard, key, marker), "4",
                StockLuaScripts.RELEASE_MARKER_TTL_SECONDS)).isEqualTo(0L);
        assertThat(redis.opsForValue().get(key)).isEqualTo("10");
        redis.delete(marker);
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

        assertThat(succeeded.get()).isEqualTo(50);          // 不能多成功一个
        assertThat(rejected.get()).isEqualTo(50);
        assertThat(redis.opsForValue().get(key)).isEqualTo("0"); // 永不为负
    }
}
