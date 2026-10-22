package com.zengbohan.aurora.inventory.stock;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.inventory.entity.StockReservation;
import com.zengbohan.aurora.inventory.mapper.StockReservationMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存预扣、释放、确认与退款回补的真实 MySQL + Redis 用例。
 * 需要本地 compose 或 CI 中的 13306/16379 中间件。
 */
@SpringBootTest(classes = StockServiceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class StockServiceIntegrationTest {

    private static final long SKU_A = 9_900_001L;
    private static final long SKU_B = 9_900_002L;
    private static final long TRADE_BASE = 9_910_000L;
    private static final long REFUND_BASE = 9_920_000L;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("com.zengbohan.aurora.inventory.mapper")
    @Import({StockService.class, StockLuaScripts.class})
    static class TestApplication {
    }

    @Autowired
    private StockService stockService;
    @Autowired
    private StockReservationMapper reservationMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM product_stock WHERE sku_id IN (?, ?)", SKU_A, SKU_B);
        jdbc.update("DELETE FROM stock_reservation WHERE trade_id BETWEEN ? AND ?",
                TRADE_BASE + 1, TRADE_BASE + 20);
        jdbc.update("DELETE FROM stock_restore WHERE refund_id BETWEEN ? AND ?",
                REFUND_BASE + 1, REFUND_BASE + 10);
        redis.delete(List.of(StockLuaScripts.key(SKU_A), StockLuaScripts.key(SKU_B)));
        for (long tradeId = TRADE_BASE + 1; tradeId <= TRADE_BASE + 20; tradeId++) {
            redis.delete(StockLuaScripts.guardKey(tradeId));
        }
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void reserveDeductsRedisAndWritesLedgerOnlyOnce() {
        seedStock(SKU_A, 10);

        stockService.reserve(TRADE_BASE + 1, List.of(item(SKU_A, 3)));
        stockService.reserve(TRADE_BASE + 1, List.of(item(SKU_A, 3)));

        assertThat(redisStock(SKU_A)).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT reserved FROM product_stock WHERE sku_id = ?", Integer.class, SKU_A))
                .isEqualTo(3);
        List<StockReservation> rows = reservationMapper.findByTrade(TRADE_BASE + 1);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getQuantity()).isEqualTo(3);
        assertThat(rows.get(0).getStatus()).isEqualTo("RESERVED");
    }

    @Test
    void insufficientSecondSkuLeavesFirstSkuUntouched() {
        seedStock(SKU_A, 5);
        seedStock(SKU_B, 1);

        assertThatThrownBy(() -> stockService.reserve(TRADE_BASE + 2,
                List.of(item(SKU_A, 3), item(SKU_B, 2))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVENTORY_INSUFFICIENT));

        assertThat(redisStock(SKU_A)).isEqualTo(5);
        assertThat(redisStock(SKU_B)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT reserved FROM product_stock WHERE sku_id = ?", Integer.class, SKU_A))
                .isZero();
        assertThat(reservationMapper.findByTrade(TRADE_BASE + 2)).isEmpty();
    }

    @Test
    void releaseRestoresRedisAndIsIdempotent() {
        seedStock(SKU_A, 10);
        stockService.reserve(TRADE_BASE + 3, List.of(item(SKU_A, 4)));

        stockService.release(TRADE_BASE + 3, List.of(item(SKU_A, 4)));
        stockService.release(TRADE_BASE + 3, List.of(item(SKU_A, 4)));

        assertThat(redisStock(SKU_A)).isEqualTo(10);
        assertThat(jdbc.queryForObject(
                "SELECT reserved FROM product_stock WHERE sku_id = ?", Integer.class, SKU_A))
                .isZero();
        assertThat(reservationMapper.find(TRADE_BASE + 3, SKU_A).getStatus())
                .isEqualTo("RELEASED");
    }

    @Test
    void confirmConsumesReservationAndRefundRestoreReturnsStockOnce() {
        seedStock(SKU_A, 10);
        stockService.reserve(TRADE_BASE + 4, List.of(item(SKU_A, 2)));

        stockService.confirm(TRADE_BASE + 4, List.of(item(SKU_A, 2)));

        assertThat(jdbc.queryForObject(
                "SELECT available FROM product_stock WHERE sku_id = ?", Integer.class, SKU_A))
                .isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT reserved FROM product_stock WHERE sku_id = ?", Integer.class, SKU_A))
                .isZero();
        assertThat(redisStock(SKU_A)).isEqualTo(8);
        assertThat(reservationMapper.find(TRADE_BASE + 4, SKU_A).getStatus())
                .isEqualTo("CONFIRMED");

        stockService.restore(REFUND_BASE + 1, TRADE_BASE + 4, List.of(item(SKU_A, 2)));
        stockService.restore(REFUND_BASE + 1, TRADE_BASE + 4, List.of(item(SKU_A, 2)));

        assertThat(jdbc.queryForObject(
                "SELECT available FROM product_stock WHERE sku_id = ?", Integer.class, SKU_A))
                .isEqualTo(10);
        assertThat(redisStock(SKU_A)).isEqualTo(10);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_restore WHERE refund_id = ?", Integer.class,
                REFUND_BASE + 1)).isEqualTo(1);
    }

    @Test
    void concurrentReservationsNeverSellTheLastUnitTwice() throws Exception {
        seedStock(SKU_A, 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                long tradeId = TRADE_BASE + 10 + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        stockService.reserve(tradeId, List.of(item(SKU_A, 1)));
                    } catch (BusinessException e) {
                        failures.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures.get()).isEqualTo(1);
        assertThat(redisStock(SKU_A)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT reserved FROM product_stock WHERE sku_id = ?", Integer.class, SKU_A))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_reservation WHERE sku_id = ? AND status = 'RESERVED'",
                Integer.class, SKU_A)).isEqualTo(1);
    }

    private void seedStock(long skuId, int available) {
        jdbc.update("INSERT INTO product_stock (sku_id, available, reserved) VALUES (?, ?, 0)",
                skuId, available);
    }

    private long redisStock(long skuId) {
        String value = redis.opsForValue().get(StockLuaScripts.key(skuId));
        assertThat(value).as("redis stock key for sku %s", skuId).isNotNull();
        return Long.parseLong(value);
    }

    private static StockDtos.StockItem item(long skuId, int quantity) {
        return new StockDtos.StockItem(skuId, quantity);
    }
}
