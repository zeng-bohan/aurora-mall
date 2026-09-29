package com.zengbohan.aurora.inventory.stock;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.idempotent.DedupStore;
import com.zengbohan.aurora.inventory.entity.ProductStock;
import com.zengbohan.aurora.inventory.mapper.ProductStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ProductStockMapper mapper;
    private StockLuaScripts lua;
    private FakeDedup dedup;
    private StockService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        mapper = mock(ProductStockMapper.class);
        lua = new StockLuaScripts();
        dedup = new FakeDedup();
        service = new StockService(redis, lua, mapper, dedup);
    }

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

    private void luaReserveReturns(long value) {
        when(redis.execute(Mockito.same(lua.reserve), anyList(), anyString())).thenReturn(value);
    }

    private ProductStock row(long skuId, int available, int reserved) {
        ProductStock row = new ProductStock();
        row.setSkuId(skuId);
        row.setAvailable(available);
        row.setReserved(reserved);
        return row;
    }

    @Test
    void reserveSuccessPassesSilently() {
        luaReserveReturns(98L);

        service.reserve(1L, 2);

        verify(redis).execute(Mockito.same(lua.reserve),
                Mockito.eq(List.of(StockLuaScripts.key(1L))), Mockito.eq("2"));
    }

    @Test
    void reserveInsufficientMapsToInventoryInsufficient() {
        luaReserveReturns(-2L);

        assertThatThrownBy(() -> service.reserve(1L, 2))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.INVENTORY_INSUFFICIENT.getCode());
    }

    @Test
    void reserveMissingKeyRebuildsFromDbViewAndRetries() {
        when(mapper.selectById(1L)).thenReturn(row(1L, 100, 20));
        // first attempt: key missing (-1); after rebuild the retry succeeds (98)
        when(redis.execute(Mockito.same(lua.reserve), anyList(), anyString()))
                .thenReturn(-1L)
                .thenReturn(98L);

        service.reserve(1L, 2);

        verify(valueOps).setIfAbsent(StockLuaScripts.key(1L), "80");
    }

    @Test
    void reserveUnknownSkuAfterRebuildMapsToNotFound() {
        when(mapper.selectById(999L)).thenReturn(null);
        when(redis.execute(Mockito.same(lua.reserve), anyList(), anyString())).thenReturn(-1L);

        assertThatThrownBy(() -> service.reserve(999L, 2))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void nullLuaResultMapsToSystemError() {
        when(redis.execute(Mockito.same(lua.reserve), anyList(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.reserve(1L, 2))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.SYSTEM_ERROR.getCode());
    }

    @Test
    void rollbackExecutesLuaAndReleasesDbReservation() {
        when(mapper.decrementReserved(1L, 3)).thenReturn(1);

        service.rollback(1L, 3);

        verify(redis).execute(Mockito.same(lua.rollback),
                Mockito.eq(List.of(StockLuaScripts.key(1L))), Mockito.eq("3"));
        verify(mapper).decrementReserved(1L, 3);
    }

    @Test
    void applyReservedEventFirstDeliveryIncrementsAtomically() {
        when(mapper.incrementReserved(1L, 5)).thenReturn(1);

        assertThat(service.applyReservedEvent("msg-1", 1L, 5)).isTrue();

        verify(mapper).incrementReserved(1L, 5);
    }

    @Test
    void applyReservedEventRedeliverySkipsDb() {
        when(mapper.incrementReserved(1L, 5)).thenReturn(1);

        assertThat(service.applyReservedEvent("msg-1", 1L, 5)).isTrue();
        assertThat(service.applyReservedEvent("msg-1", 1L, 5)).isFalse();

        verify(mapper, Mockito.times(1)).incrementReserved(1L, 5);
    }

    @Test
    void applyReservedEventUnknownSkuThrowsNotFound() {
        when(mapper.incrementReserved(999L, 5)).thenReturn(0);

        assertThatThrownBy(() -> service.applyReservedEvent("msg-2", 999L, 5))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void applyReservedEventIsTransactional() throws NoSuchMethodException {
        // the dedup row and the reserved update must share one transaction:
        // a crash between them rolls the dedup back so redelivery re-applies.
        // (rollback itself needs a real tx manager -> covered by the live
        // smoke chain; here we pin the contract)
        assertThat(StockService.class
                .getMethod("applyReservedEvent", String.class, long.class, int.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                .isNotNull();
    }

    @Test
    void reconcileRebuildsMissingKeysAndLogsDeltas() {
        ProductStock seeded = row(1L, 100, 20);
        when(mapper.selectList(any())).thenReturn(List.of(seeded));
        when(valueOps.get(StockLuaScripts.key(1L))).thenReturn(null).thenReturn("80");

        service.reconcile();
        service.reconcile();

        verify(valueOps, Mockito.atLeastOnce()).setIfAbsent(StockLuaScripts.key(1L), "80");
        assertThat(seeded.getAvailable() - seeded.getReserved()).isEqualTo(80);
    }
}
