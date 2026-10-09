package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import com.zengbohan.aurora.seckill.entity.SeckillStock;
import com.zengbohan.aurora.seckill.mapper.SeckillStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预热：Redis 快照以 DB 为准（幂等覆盖而非累加）；已结束/不存在的活动拒绝；
 * 键布局与 S2 的 Lua 契约一致。
 */
class SeckillPreheatServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-09T10:00:00");

    private SeckillActivityService activityService;
    private SeckillStockMapper stockMapper;
    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private SeckillPreheatService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        activityService = mock(SeckillActivityService.class);
        stockMapper = mock(SeckillStockMapper.class);
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        service = new SeckillPreheatService(activityService, stockMapper, redis) {
            @Override
            LocalDateTime now() {
                return NOW;
            }
        };
    }

    @Test
    void preheatWritesDbAvailableAsAbsoluteSnapshot() {
        when(activityService.require(7L)).thenReturn(activity(7L, NOW.plusHours(1), NOW.plusHours(2)));
        SeckillStock stock = new SeckillStock();
        stock.setAvailable(37);
        when(stockMapper.selectById(7L)).thenReturn(stock);

        SeckillPreheatService.PreheatResult result = service.preheat(7L);

        assertThat(result.activityId()).isEqualTo(7L);
        assertThat(result.stock()).isEqualTo(37);
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(hashOps).putAll(eq("seckill:activity:7"), fields.capture());
        Map<String, String> written = fields.getValue();
        assertThat(written)
                .containsEntry("stock", "37")
                .containsEntry("perUserLimit", "1")
                .containsEntry("totalStock", "100")
                .containsKeys("startAt", "endAt");
        // 覆盖后补 TTL：结束后仍保留一天（窗口外 Lua 也会拒绝，TTL 只是清理）
        verify(redis).expire(eq("seckill:activity:7"), any(Duration.class));
    }

    @Test
    void preheatIsIdempotentOverlay() {
        // 重复预热两次：两次写入的都是 DB 的绝对值（37），而不是在旧值上累加
        when(activityService.require(7L)).thenReturn(activity(7L, NOW.plusHours(1), NOW.plusHours(2)));
        SeckillStock stock = new SeckillStock();
        stock.setAvailable(37);
        when(stockMapper.selectById(7L)).thenReturn(stock);

        service.preheat(7L);
        service.preheat(7L);

        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(hashOps, org.mockito.Mockito.times(2)).putAll(eq("seckill:activity:7"), fields.capture());
        assertThat(fields.getAllValues().get(0)).containsEntry("stock", "37");
        assertThat(fields.getAllValues().get(1)).containsEntry("stock", "37");
    }

    @Test
    void endedActivityIsRejectedWithoutTouchingRedis() {
        when(activityService.require(7L)).thenReturn(activity(7L, NOW.minusHours(2), NOW.minusHours(1)));

        assertThatThrownBy(() -> service.preheat(7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECKILL_ENDED);
        verify(redis, never()).opsForHash();
    }

    @Test
    void missingActivityPropagatesNotFound() {
        when(activityService.require(404L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.preheat(404L))
                .isInstanceOf(BusinessException.class);
        verify(redis, never()).opsForHash();
    }

    @Test
    void missingStockRowWritesZeroStock() {
        // 防御：库存行缺失（半开状态）时预热 0 而不是失败——S2 的 Lua 会拒绝 0 余量
        when(activityService.require(7L)).thenReturn(activity(7L, NOW.plusHours(1), NOW.plusHours(2)));
        when(stockMapper.selectById(7L)).thenReturn(null);

        assertThat(service.preheat(7L).stock()).isZero();
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(hashOps).putAll(eq("seckill:activity:7"), fields.capture());
        assertThat(fields.getValue()).containsEntry("stock", "0");
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
