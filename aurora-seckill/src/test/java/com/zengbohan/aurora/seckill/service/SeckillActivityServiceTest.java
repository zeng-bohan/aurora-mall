package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import com.zengbohan.aurora.seckill.entity.SeckillStock;
import com.zengbohan.aurora.seckill.mapper.SeckillActivityMapper;
import com.zengbohan.aurora.seckill.mapper.SeckillStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 活动创建的校验矩阵与视图推导：固定时钟把"未开始/进行中/已结束"测成确定性的。
 */
class SeckillActivityServiceTest {

    private static final LocalDateTime NOW =
            LocalDateTime.parse("2026-10-09T10:00:00");

    private SeckillActivityMapper activityMapper;
    private SeckillStockMapper stockMapper;
    private SeckillActivityService service;

    @BeforeEach
    void setUp() {
        activityMapper = mock(SeckillActivityMapper.class);
        stockMapper = mock(SeckillStockMapper.class);
        service = new SeckillActivityService(activityMapper, stockMapper) {
            @Override
            LocalDateTime now() {
                return NOW;
            }
        };
    }

    private static LocalDateTime start() {
        return NOW.plusHours(1);
    }

    private static LocalDateTime end() {
        return NOW.plusHours(2);
    }

    @Test
    void createInsertsActivityAndStockRowTogether() {
        when(activityMapper.insert(any(SeckillActivity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SeckillActivity.class).setId(7L);
            return 1;
        });

        SeckillActivity created = service.create("  双11爆款  ", 1L,
                new BigDecimal("9.90"), 100, 1, start(), end());

        assertThat(created.getId()).isEqualTo(7L);
        ArgumentCaptor<SeckillActivity> activity = ArgumentCaptor.forClass(SeckillActivity.class);
        verify(activityMapper).insert(activity.capture());
        assertThat(activity.getValue().getTitle()).as("标题去空白").isEqualTo("双11爆款");
        assertThat(activity.getValue().getSkuId()).isEqualTo(1L);
        assertThat(activity.getValue().getSeckillPrice()).isEqualByComparingTo("9.90");
        assertThat(activity.getValue().getTotalStock()).isEqualTo(100);

        ArgumentCaptor<SeckillStock> stock = ArgumentCaptor.forClass(SeckillStock.class);
        verify(stockMapper).insert(stock.capture());
        assertThat(stock.getValue().getActivityId()).isEqualTo(7L);
        assertThat(stock.getValue().getTotal()).isEqualTo(100);
        assertThat(stock.getValue().getAvailable()).as("初始余量=总量").isEqualTo(100);
    }

    @Test
    void blankTitleRejectedWithoutAnyInsert() {
        assertThatThrownBy(() -> service.create("  ", 1L, new BigDecimal("9.90"), 100, 1, start(), end()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);
        verify(activityMapper, never()).insert(any(SeckillActivity.class));
    }

    @Test
    void nonPositivePriceOrStockRejected() {
        assertThatThrownBy(() -> service.create("t", 1L, new BigDecimal("0"), 100, 1, start(), end()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create("t", 1L, new BigDecimal("9.90"), 0, 1, start(), end()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create("t", 1L, new BigDecimal("9.90"), 100, 0, start(), end()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create("t", 1L, null, 100, 1, start(), end()))
                .isInstanceOf(BusinessException.class);
        verify(activityMapper, never()).insert(any(SeckillActivity.class));
    }

    @Test
    void invertedOrPastWindowRejected() {
        assertThatThrownBy(() -> service.create("t", 1L, new BigDecimal("9.90"), 100, 1, end(), start()))
                .as("开始晚于结束")
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create("t", 1L, new BigDecimal("9.90"), 100, 1,
                NOW.minusHours(2), NOW.minusHours(1)))
                .as("结束时间在过去")
                .isInstanceOf(BusinessException.class);
        verify(activityMapper, never()).insert(any(SeckillActivity.class));
    }

    @Test
    void requireMissingActivityThrowsNotFound() {
        when(activityMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.require(404L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void viewCarriesPhaseAndDbStock() {
        SeckillActivity running = activity(1L, NOW.minusHours(1), NOW.plusHours(1));
        when(activityMapper.selectById(1L)).thenReturn(running);
        SeckillStock stock = new SeckillStock();
        stock.setAvailable(42);
        when(stockMapper.selectById(1L)).thenReturn(stock);

        SeckillActivityService.SeckillActivityView view = service.viewOf(1L);
        assertThat(view.phase()).isEqualTo("RUNNING");
        assertThat(view.availableStock()).isEqualTo(42);

        SeckillActivity upcoming = activity(2L, NOW.plusHours(1), NOW.plusHours(2));
        when(activityMapper.selectById(2L)).thenReturn(upcoming);
        assertThat(service.viewOf(2L).phase()).isEqualTo("NOT_STARTED");
    }

    @Test
    void listNotEndedPutsRunningFirst() {
        SeckillActivity upcoming = activity(1L, NOW.plusHours(1), NOW.plusHours(3));
        SeckillActivity running = activity(2L, NOW.minusHours(1), NOW.plusHours(1));
        when(activityMapper.selectList(any())).thenReturn(List.of(upcoming, running));

        List<SeckillActivityService.SeckillActivityView> views = service.listNotEndedViews();
        assertThat(views).extracting(SeckillActivityService.SeckillActivityView::phase)
                .containsExactly("RUNNING", "NOT_STARTED");
    }

    private static SeckillActivity activity(long id, LocalDateTime startAt, LocalDateTime endAt) {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(id);
        activity.setTitle("活动" + id);
        activity.setSkuId(1L);
        activity.setSeckillPrice(new BigDecimal("9.90"));
        activity.setTotalStock(100);
        activity.setPerUserLimit(1);
        activity.setStartAt(startAt);
        activity.setEndAt(endAt);
        return activity;
    }
}
