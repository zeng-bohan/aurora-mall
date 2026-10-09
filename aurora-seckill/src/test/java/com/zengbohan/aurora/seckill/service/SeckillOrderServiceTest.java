package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下单闸门与补偿：预扣返回码到业务码的 1:1 映射，以及"落单没成必须把预扣还回去"。
 */
class SeckillOrderServiceTest {

    private static final long ACTIVITY_ID = 7L;
    private static final long USER_ID = 1L;

    private SeckillActivityService activityService;
    private SeckillOrderWriter orderWriter;
    private StringRedisTemplate redis;
    private SeckillLuaScripts scripts;
    private SeckillOrderService service;

    @BeforeEach
    void setUp() {
        activityService = mock(SeckillActivityService.class);
        orderWriter = mock(SeckillOrderWriter.class);
        redis = mock(StringRedisTemplate.class);
        scripts = new SeckillLuaScripts();
        service = new SeckillOrderService(activityService, orderWriter, scripts, redis);
    }

    @Test
    void reserveCodesMapToBusinessCodesOneToOne() {
        assertReserveCodeRejected(SeckillOrderService.RESERVE_NOT_PREPARED, ErrorCode.SECKILL_NOT_READY);
        assertReserveCodeRejected(SeckillOrderService.RESERVE_NOT_STARTED, ErrorCode.SECKILL_NOT_STARTED);
        assertReserveCodeRejected(SeckillOrderService.RESERVE_ENDED, ErrorCode.SECKILL_ENDED);
        assertReserveCodeRejected(SeckillOrderService.RESERVE_SOLD_OUT, ErrorCode.SECKILL_SOLD_OUT);
        assertReserveCodeRejected(SeckillOrderService.RESERVE_ALREADY_BOUGHT, ErrorCode.SECKILL_ALREADY_BOUGHT);
        // 被闸门拒绝的请求不该触达落单，也不该补偿（压根没预扣过）
        verifyNoInteractions(orderWriter);
        verify(redis, never()).execute(eq(scripts.compensate), anyList(), any());
    }

    @Test
    void nullReserveResultIsTreatedAsNotReadyRatherThanSoldOut() {
        // 未预热/脚本异常返回的"空结果"必须落 NOT_READY：误报售罄会把运营引到错误方向
        assertReserveCodeRejected(null, ErrorCode.SECKILL_NOT_READY);
    }

    @Test
    void reservedRequestPlacesOrderAndNeedsNoCompensation() {
        stubReserve(SeckillOrderService.RESERVE_OK);
        SeckillActivity activity = activity();
        when(activityService.require(ACTIVITY_ID)).thenReturn(activity);
        when(orderWriter.place(activity, USER_ID)).thenReturn(99L);

        assertThat(service.buy(ACTIVITY_ID, USER_ID)).isEqualTo(99L);
        verify(redis, never()).execute(eq(scripts.compensate), anyList(), any());
    }

    @Test
    void dbFailureCompensatesReservationAndRethrows() {
        stubReserve(SeckillOrderService.RESERVE_OK);
        when(activityService.require(ACTIVITY_ID)).thenReturn(activity());
        when(orderWriter.place(any(SeckillActivity.class), eq(USER_ID)))
                .thenThrow(new BusinessException(ErrorCode.SECKILL_SOLD_OUT, "DB 库存已尽"));

        assertThatThrownBy(() -> service.buy(ACTIVITY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECKILL_SOLD_OUT);
        verify(redis).execute(eq(scripts.compensate), anyList(), any());
    }

    @Test
    void duplicateOrderUniqueKeyFallsBackToAlreadyBought() {
        // Redis 标记丢失（重启）时由 DB 唯一键兜底，对调用方语义仍是"已参与过"，且预扣要还回去
        stubReserve(SeckillOrderService.RESERVE_OK);
        when(activityService.require(ACTIVITY_ID)).thenReturn(activity());
        when(orderWriter.place(any(SeckillActivity.class), eq(USER_ID)))
                .thenThrow(new DuplicateKeyException("uk_seckill_order_user"));

        assertThatThrownBy(() -> service.buy(ACTIVITY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECKILL_ALREADY_BOUGHT);
        verify(redis).execute(eq(scripts.compensate), anyList(), any());
    }

    private void assertReserveCodeRejected(Long reserveResult, ErrorCode expected) {
        stubReserve(reserveResult);
        assertThatThrownBy(() -> service.buy(ACTIVITY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    // doReturn 避开 execute(RedisScript<T>, ...) 的泛型推断问题。
    private void stubReserve(Long result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(), any(), any());
    }

    private static SeckillActivity activity() {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(ACTIVITY_ID);
        activity.setTitle("秒杀");
        activity.setSkuId(1L);
        activity.setSeckillPrice(new BigDecimal("9.90"));
        activity.setTotalStock(10);
        activity.setPerUserLimit(1);
        activity.setStartAt(LocalDateTime.now().minusMinutes(1));
        activity.setEndAt(LocalDateTime.now().plusMinutes(30));
        return activity;
    }
}
