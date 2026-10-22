package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.idempotent.DedupStore;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import com.zengbohan.aurora.seckill.entity.SeckillOrder;
import com.zengbohan.aurora.seckill.mapper.SeckillOrderMapper;
import com.zengbohan.aurora.seckill.mapper.SeckillStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 落单写入：订单字段取自活动（成交价是下单时刻的活动价），DB 侧条件扣减失败即整体失败；
 * 消息去重与订单写入共享事务，去重命中时不再写订单。
 */
class SeckillOrderWriterTest {

    private SeckillOrderMapper orderMapper;
    private SeckillStockMapper stockMapper;
    private DedupStore dedupStore;
    private SeckillOrderWriter writer;

    @BeforeEach
    void setUp() {
        orderMapper = mock(SeckillOrderMapper.class);
        stockMapper = mock(SeckillStockMapper.class);
        dedupStore = mock(DedupStore.class);
        writer = new SeckillOrderWriter(orderMapper, stockMapper, dedupStore);
    }

    @Test
    void placeOnceInsertsDedupThenOrderAndStock() {
        when(dedupStore.tryInsert(SeckillOrderWriter.DEDUP_BIZ_TYPE, "msg-1")).thenReturn(true);
        when(orderMapper.insert(any(SeckillOrder.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SeckillOrder.class).setId(99L);
            return 1;
        });
        when(stockMapper.deduct(7L)).thenReturn(1);

        SeckillOrderWriter.PlaceOutcome outcome = writer.placeOnce("msg-1", activity(), 5L);

        assertThat(outcome.duplicate()).isFalse();
        assertThat(outcome.orderId()).isEqualTo(99L);
        verify(dedupStore).tryInsert(SeckillOrderWriter.DEDUP_BIZ_TYPE, "msg-1");
        verify(stockMapper).deduct(7L);
    }

    @Test
    void duplicateMessageSkipsOrderWrite() {
        when(dedupStore.tryInsert(SeckillOrderWriter.DEDUP_BIZ_TYPE, "msg-1")).thenReturn(false);

        SeckillOrderWriter.PlaceOutcome outcome = writer.placeOnce("msg-1", activity(), 5L);

        assertThat(outcome.duplicate()).isTrue();
        verify(orderMapper, never()).insert(any(SeckillOrder.class));
        verify(stockMapper, never()).deduct(7L);
    }

    @Test
    void failedOrderRollsBackDedupRowWithSameTransaction() {
        // 事务边界由 @Transactional 保证：这里验证顺序（去重先行）和失败传播，
        // 真实回滚由 SeckillOrderTransactionIntegrationTest 对 MySQL 验证
        when(dedupStore.tryInsert(SeckillOrderWriter.DEDUP_BIZ_TYPE, "msg-1")).thenReturn(true);
        when(orderMapper.insert(any(SeckillOrder.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SeckillOrder.class).setId(99L);
            return 1;
        });
        when(stockMapper.deduct(7L)).thenReturn(0);

        assertThatThrownBy(() -> writer.placeOnce("msg-1", activity(), 5L))
                .isInstanceOf(BusinessException.class);
        verify(dedupStore).tryInsert(SeckillOrderWriter.DEDUP_BIZ_TYPE, "msg-1");
    }

    @Test
    void placeWritesOrderThenDeductsDbStock() {
        when(orderMapper.insert(any(SeckillOrder.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SeckillOrder.class).setId(99L);
            return 1;
        });
        when(stockMapper.deduct(7L)).thenReturn(1);

        assertThat(writer.place(activity(), 5L)).isEqualTo(99L);

        ArgumentCaptor<SeckillOrder> order = ArgumentCaptor.forClass(SeckillOrder.class);
        verify(orderMapper).insert(order.capture());
        assertThat(order.getValue().getActivityId()).isEqualTo(7L);
        assertThat(order.getValue().getUserId()).isEqualTo(5L);
        assertThat(order.getValue().getSkuId()).isEqualTo(1L);
        assertThat(order.getValue().getPrice()).isEqualByComparingTo("9.90");
        assertThat(order.getValue().getStatus()).isEqualTo("CREATED");
        verify(stockMapper).deduct(7L);
    }

    @Test
    void dbStockExhaustedThrowsSoldOut() {
        when(stockMapper.deduct(7L)).thenReturn(0);

        assertThatThrownBy(() -> writer.place(activity(), 5L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECKILL_SOLD_OUT);
    }

    private static SeckillActivity activity() {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(7L);
        activity.setSkuId(1L);
        activity.setSeckillPrice(new BigDecimal("9.90"));
        return activity;
    }
}
