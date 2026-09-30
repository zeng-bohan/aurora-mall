package com.zengbohan.aurora.inventory.stock;

import com.zengbohan.aurora.common.idempotent.DedupStore;
import com.zengbohan.aurora.inventory.entity.ProductStock;
import com.zengbohan.aurora.inventory.mapper.ProductStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** applyPaidEvent（支付确认的库存位移）——支付/关单竞态的爆点，专项覆盖。 */
class ApplyPaidEventTest {

    private StringRedisTemplate redis;
    private ProductStockMapper mapper;
    private DedupStore dedup;
    private StockService service;

    private static class FakeDedup implements DedupStore {
        private final Set<String> rows = new HashSet<>();

        @Override
        public boolean tryInsert(String bizType, String bizKey) {
            return rows.add(bizType + ":" + bizKey);
        }

        @Override
        public void remove(String bizType, String bizKey) {
            rows.remove(bizType + ":" + bizKey);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        mapper = mock(ProductStockMapper.class);
        var lua = new StockLuaScripts();
        dedup = new FakeDedup();
        var txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new StockService(redis, lua, mapper, dedup, new TransactionTemplate(txManager));
    }

    private ProductStock row(int available, int reserved) {
        ProductStock row = new ProductStock();
        row.setSkuId(1L);
        row.setAvailable(available);
        row.setReserved(reserved);
        return row;
    }

    @Test
    void firstDeliveryConfirmsPaymentAtomically() {
        when(mapper.confirmPayment(1L, 3)).thenReturn(1);

        assertThat(service.applyPaidEvent("msg-1", 1L, 3)).isTrue();

        Mockito.verify(mapper).confirmPayment(1L, 3);
    }

    @Test
    void redeliveryIsDeduped() {
        when(mapper.confirmPayment(1L, 3)).thenReturn(1);

        assertThat(service.applyPaidEvent("msg-1", 1L, 3)).isTrue();
        assertThat(service.applyPaidEvent("msg-1", 1L, 3)).isFalse();

        Mockito.verify(mapper, Mockito.times(1)).confirmPayment(1L, 3);
    }

    @Test
    void missingReservationThrowsSoRedeliveryRetries() {
        // reserved 事件还在途：confirmPayment 0 行 → 抛异常 → 事务回滚 dedup 行 → 重投递重试
        when(mapper.confirmPayment(1L, 3)).thenReturn(0);
        when(mapper.selectById(1L)).thenReturn(row(97, 0));

        assertThatThrownBy(() -> service.applyPaidEvent("msg-2", 1L, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("will retry");
    }

    @Test
    void unknownSkuFollowsTheRetryPath() {
        // 未知 SKU 与在途预留同语义：重试可重入（对账任务会发现 sku 不存在）
        when(mapper.confirmPayment(999L, 3)).thenReturn(0);
        when(mapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.applyPaidEvent("msg-3", 999L, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("will retry");
    }
}
