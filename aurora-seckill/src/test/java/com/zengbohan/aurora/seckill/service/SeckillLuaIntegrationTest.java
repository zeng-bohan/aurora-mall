package com.zengbohan.aurora.seckill.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 秒杀 Lua 对着真实 redis 执行（本机开发环境是 localhost:16379）。
 * 无 redis 可达时自动跳过，因此没有 redis 的 CI runner 依然保持绿色——
 * 单元测试 mock 了 execute()，只能证明返回码映射，证明不了脚本本身的语义：
 * 窗口外不动库存、一人一单、售罄拒绝、补偿恰好一次（重放不会把库存加回去第二次）、
 * 并发预扣不超卖。
 *
 * 本地运行：docker compose up -d redis
 */
class SeckillLuaIntegrationTest {

    private static final String ACTIVITY_HASH_FIELDS_START = "startAt";
    private static final String ACTIVITY_HASH_FIELDS_END = "endAt";
    private static final String ACTIVITY_HASH_FIELDS_STOCK = "stock";

    private static final String HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "16379"));

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private SeckillLuaScripts scripts;
    /** 每个用例用自己的活动 id，避免与历史 key 互相干扰。 */
    private long activityId;

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
        Assumptions.assumeTrue(redisReachable(), "redis not reachable at " + HOST + ":" + PORT);
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(HOST, PORT);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        scripts = new SeckillLuaScripts();
        activityId = System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            redis.delete(SeckillLuaScripts.activityKey(activityId));
            redis.delete(SeckillLuaScripts.boughtKey(activityId));
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void activityWithoutPreheatIsRejected() {
        assertThat(reserve(1L)).isEqualTo(-1L);
    }

    @Test
    void windowIsEnforcedAndStockUntouched() {
        long now = System.currentTimeMillis();
        preheat(now + 60_000, now + 120_000, 5);
        assertThat(reserve(1L)).as("未开始").isEqualTo(2L);

        preheat(now - 120_000, now - 60_000, 5);
        assertThat(reserve(1L)).as("已结束").isEqualTo(3L);

        assertThat(stock()).as("窗口外不该动库存").isEqualTo("5");
    }

    @Test
    void onePersonOneOrderIsEnforced() {
        long now = System.currentTimeMillis();
        preheat(now - 1_000, now + 60_000, 3);

        assertThat(reserve(1L)).isEqualTo(1L);
        assertThat(reserve(1L)).as("同一用户再次抢购被拒").isEqualTo(5L);
        assertThat(reserve(2L)).as("另一用户可以抢").isEqualTo(1L);
        assertThat(stock()).isEqualTo("1");
    }

    @Test
    void soldOutWhenStockExhausted() {
        long now = System.currentTimeMillis();
        preheat(now - 1_000, now + 60_000, 1);

        assertThat(reserve(1L)).isEqualTo(1L);
        assertThat(reserve(2L)).as("库存耗尽").isEqualTo(4L);
        assertThat(stock()).isEqualTo("0");
    }

    @Test
    void compensateRestoresStockExactlyOnce() {
        long now = System.currentTimeMillis();
        preheat(now - 1_000, now + 60_000, 2);

        assertThat(reserve(1L)).isEqualTo(1L);
        assertThat(stock()).isEqualTo("1");

        assertThat(compensate(1L)).as("第一次补偿归还名额").isEqualTo(1L);
        assertThat(stock()).isEqualTo("2");

        assertThat(compensate(1L)).as("重复补偿是空操作").isEqualTo(0L);
        assertThat(stock()).as("补偿重放不能造成超卖").isEqualTo("2");
        assertThat(reserve(1L)).as("归还后该用户可以重新抢").isEqualTo(1L);
    }

    @Test
    void concurrentReserveNeverOversellsAndNeverHitsFractionalStock() throws Exception {
        long now = System.currentTimeMillis();
        int totalStock = 20;
        preheat(now - 1_000, now + 60_000, totalStock);

        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long userId = i + 1;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    if (reserve(userId) == 1L) {
                        succeeded.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(succeeded.get()).as("成功数正好等于库存量").isEqualTo(totalStock);
        assertThat(stock()).as("余量落到 0 且不为负").isEqualTo("0");
    }

    private void preheat(long startAt, long endAt, int stock) {
        Map<String, String> fields = new HashMap<>();
        fields.put(ACTIVITY_HASH_FIELDS_START, String.valueOf(startAt));
        fields.put(ACTIVITY_HASH_FIELDS_END, String.valueOf(endAt));
        fields.put("perUserLimit", "1");
        fields.put("totalStock", String.valueOf(stock));
        fields.put(ACTIVITY_HASH_FIELDS_STOCK, String.valueOf(stock));
        redis.opsForHash().putAll(SeckillLuaScripts.activityKey(activityId), fields);
    }

    private long reserve(long userId) {
        return redis.execute(scripts.reserve, keys(),
                String.valueOf(userId), String.valueOf(System.currentTimeMillis()));
    }

    private long compensate(long userId) {
        return redis.execute(scripts.compensate, keys(), String.valueOf(userId));
    }

    private List<String> keys() {
        return List.of(SeckillLuaScripts.activityKey(activityId), SeckillLuaScripts.boughtKey(activityId));
    }

    private String stock() {
        Object value = redis.opsForHash().get(SeckillLuaScripts.activityKey(activityId),
                ACTIVITY_HASH_FIELDS_STOCK);
        return value == null ? null : value.toString();
    }
}
