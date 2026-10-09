package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.seckill.ratelimit.SeckillRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
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
 * 并发预扣不超卖、以及受理/失败结果与库存同脚本原子写入。
 *
 * 本地运行：docker compose up -d redis
 */
class SeckillLuaIntegrationTest {

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
            redis.delete(SeckillLuaScripts.keys(activityId));
            redis.delete(SeckillRateLimiter.activityKey(activityId));
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
        assertThat(result(1L)).as("窗口外不该产生受理记录").isNull();
    }

    @Test
    void onePersonOneOrderIsEnforcedAndReserveRecordsPending() {
        long now = System.currentTimeMillis();
        preheat(now - 1_000, now + 60_000, 3);

        assertThat(reserve(1L)).isEqualTo(1L);
        assertThat(result(1L)).as("受理结果与扣减同脚本写入").isEqualTo(SeckillLuaScripts.RESULT_PENDING);
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
        assertThat(result(2L)).as("被拒的请求不该有受理记录").isNull();
    }

    @Test
    void compensateRestoresStockExactlyOnceAndRecordsReason() {
        long now = System.currentTimeMillis();
        preheat(now - 1_000, now + 60_000, 2);

        assertThat(reserve(1L)).isEqualTo(1L);
        assertThat(stock()).isEqualTo("1");

        String reason = ErrorCode.SECKILL_SOLD_OUT.name();
        assertThat(compensate(1L, reason)).as("第一次补偿归还名额").isEqualTo(1L);
        assertThat(stock()).isEqualTo("2");
        assertThat(result(1L)).as("归还后结果改为失败原因，客户端不再空等")
                .isEqualTo(SeckillLuaScripts.RESULT_FAIL_PREFIX + reason);

        assertThat(compensate(1L, ErrorCode.SYSTEM_ERROR.name())).as("重复补偿是空操作").isEqualTo(0L);
        assertThat(stock()).as("补偿重放不能造成超卖").isEqualTo("2");
        assertThat(result(1L)).as("重复补偿不改写首次失败原因")
                .isEqualTo(SeckillLuaScripts.RESULT_FAIL_PREFIX + reason);
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

    @Test
    void rateLimiterAllowsExactlyTheQuotaWithinTheWindow() {
        SeckillRateLimiter limiter = new SeckillRateLimiter(redis);
        String key = SeckillRateLimiter.activityKey(activityId);
        int limit = 5;
        Duration window = Duration.ofSeconds(5);

        for (int i = 1; i <= limit; i++) {
            assertThat(limiter.tryAcquire(key, limit, window)).as("第 " + i + " 个请求放行").isTrue();
        }
        assertThat(limiter.tryAcquire(key, limit, window)).as("超出配额被拒").isFalse();
    }

    @Test
    void rateLimiterRecoversAfterTheWindowSlides() throws Exception {
        SeckillRateLimiter limiter = new SeckillRateLimiter(redis);
        String key = SeckillRateLimiter.activityKey(activityId);
        Duration window = Duration.ofSeconds(1);

        assertThat(limiter.tryAcquire(key, 1, window)).isTrue();
        assertThat(limiter.tryAcquire(key, 1, window)).as("窗口内配额已用尽").isFalse();

        Thread.sleep(1100);
        assertThat(limiter.tryAcquire(key, 1, window)).as("窗口滑过后配额恢复").isTrue();
    }

    private void preheat(long startAt, long endAt, int stock) {
        Map<String, String> fields = new HashMap<>();
        fields.put("startAt", String.valueOf(startAt));
        fields.put("endAt", String.valueOf(endAt));
        fields.put("perUserLimit", "1");
        fields.put("totalStock", String.valueOf(stock));
        fields.put("stock", String.valueOf(stock));
        redis.opsForHash().putAll(SeckillLuaScripts.activityKey(activityId), fields);
    }

    private long reserve(long userId) {
        return redis.execute(scripts.reserve, SeckillLuaScripts.keys(activityId),
                String.valueOf(userId), String.valueOf(System.currentTimeMillis()));
    }

    private long compensate(long userId, String reason) {
        return redis.execute(scripts.compensate, SeckillLuaScripts.keys(activityId),
                String.valueOf(userId), reason);
    }

    private String stock() {
        return field(SeckillLuaScripts.activityKey(activityId), "stock");
    }

    private String result(long userId) {
        return field(SeckillLuaScripts.resultKey(activityId), String.valueOf(userId));
    }

    private String field(String key, String field) {
        Object value = redis.opsForHash().get(key, field);
        return value == null ? null : value.toString();
    }
}
