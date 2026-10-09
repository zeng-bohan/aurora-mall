package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.idempotent.Idempotent;
import com.zengbohan.aurora.common.idempotent.Strategy;
import com.zengbohan.aurora.seckill.dto.SeckillBuyView;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import com.zengbohan.aurora.seckill.entity.SeckillOrder;
import com.zengbohan.aurora.seckill.mapper.SeckillOrderMapper;
import com.zengbohan.aurora.seckill.mq.SeckillEventPublisher;
import com.zengbohan.aurora.seckill.ratelimit.SeckillRateLimitProperties;
import com.zengbohan.aurora.seckill.ratelimit.SeckillRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下单闸门、两种落单模式、异步落单的幂等与补偿语义。
 */
class SeckillOrderServiceTest {

    private static final long ACTIVITY_ID = 7L;
    private static final long USER_ID = 1L;
    private static final String MESSAGE_ID = "msg-1";
    private static final String USER_FIELD = String.valueOf(USER_ID);

    private SeckillActivityService activityService;
    private SeckillOrderWriter orderWriter;
    private SeckillEventPublisher publisher;
    private SeckillOrderMapper orderMapper;
    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hash;
    private SeckillLuaScripts scripts;
    private SeckillRateLimiter rateLimiter;
    private SeckillRateLimitProperties rateLimitProperties;

    @BeforeEach
    void setUp() {
        activityService = mock(SeckillActivityService.class);
        orderWriter = mock(SeckillOrderWriter.class);
        publisher = mock(SeckillEventPublisher.class);
        orderMapper = mock(SeckillOrderMapper.class);
        redis = mock(StringRedisTemplate.class);
        hash = mock(HashOperations.class);
        doReturn(hash).when(redis).opsForHash();
        scripts = new SeckillLuaScripts();
        rateLimiter = mock(SeckillRateLimiter.class);
        // 默认配额充足：限流不是本类其余用例的变量，只有专门的用例才改成拒绝
        doReturn(true).when(rateLimiter).tryAcquire(anyString(), anyInt(), any(Duration.class));
        rateLimitProperties = new SeckillRateLimitProperties();
    }

    // ---------- 闸门 ----------

    @Test
    void reserveCodesMapToBusinessCodesOneToOne() {
        assertReserveCodeRejected(SeckillOrderService.RESERVE_NOT_PREPARED, ErrorCode.SECKILL_NOT_READY);
        assertReserveCodeRejected(SeckillOrderService.RESERVE_NOT_STARTED, ErrorCode.SECKILL_NOT_STARTED);
        assertReserveCodeRejected(SeckillOrderService.RESERVE_ENDED, ErrorCode.SECKILL_ENDED);
        assertReserveCodeRejected(SeckillOrderService.RESERVE_SOLD_OUT, ErrorCode.SECKILL_SOLD_OUT);
        assertReserveCodeRejected(SeckillOrderService.RESERVE_ALREADY_BOUGHT, ErrorCode.SECKILL_ALREADY_BOUGHT);
        // 被闸门拒绝的请求既不落单也不补偿（压根没预扣过）
        verifyNoInteractions(orderWriter, publisher, activityService, orderMapper);
        verify(redis, never()).execute(eq(scripts.compensate), anyList(), any(), any());
    }

    @Test
    void nullReserveResultIsTreatedAsNotReadyRatherThanSoldOut() {
        // 未预热/脚本异常返回的"空结果"必须落 NOT_READY：误报售罄会把运营引到错误方向
        assertReserveCodeRejected(null, ErrorCode.SECKILL_NOT_READY);
    }

    @Test
    void unknownOrderModeFailsFast() {
        // 模式写错必须启动即失败；静默回退会让实验口径失真
        assertThatThrownBy(() -> service("async"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order-mode");
    }

    // ---------- sync 模式（对照基线） ----------

    @Test
    void syncModePlacesOrderAndReturnsPlaced() {
        stubReserve(SeckillOrderService.RESERVE_OK);
        SeckillActivity activity = activity();
        when(activityService.require(ACTIVITY_ID)).thenReturn(activity);
        when(orderWriter.place(activity, USER_ID)).thenReturn(99L);

        assertThat(service("sync").buy(ACTIVITY_ID, USER_ID))
                .isEqualTo(new SeckillBuyView(SeckillBuyView.STATUS_PLACED, 99L));
        verify(hash).put(SeckillLuaScripts.resultKey(ACTIVITY_ID), USER_FIELD,
                SeckillLuaScripts.RESULT_ORDER_PREFIX + 99L);
        verify(redis, never()).execute(eq(scripts.compensate), anyList(), any(), any());
    }

    @Test
    void syncModeDbFailureCompensatesAndRethrows() {
        stubReserve(SeckillOrderService.RESERVE_OK);
        when(activityService.require(ACTIVITY_ID)).thenReturn(activity());
        when(orderWriter.place(any(SeckillActivity.class), eq(USER_ID)))
                .thenThrow(new BusinessException(ErrorCode.SECKILL_SOLD_OUT, "DB 库存已尽"));

        assertThatThrownBy(() -> service("sync").buy(ACTIVITY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECKILL_SOLD_OUT);
        verifyCompensated(ErrorCode.SECKILL_SOLD_OUT);
    }

    @Test
    void syncModeDuplicateOrderCompensatesAndReportsAlreadyBought() {
        // Redis 标记丢失后重抢：这次预扣是新鲜的，订单却已存在 → 归还名额
        stubReserve(SeckillOrderService.RESERVE_OK);
        when(activityService.require(ACTIVITY_ID)).thenReturn(activity());
        when(orderWriter.place(any(SeckillActivity.class), eq(USER_ID)))
                .thenThrow(new DuplicateKeyException("uk_seckill_order_user"));

        assertThatThrownBy(() -> service("sync").buy(ACTIVITY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECKILL_ALREADY_BOUGHT);
        verifyCompensated(ErrorCode.SECKILL_ALREADY_BOUGHT);
    }

    // ---------- mq 模式（主链路） ----------

    @Test
    void mqModePublishesAndReturnsQueuedWithoutTouchingDb() {
        // 削峰的要点：请求路径只碰 Redis，DB 读写在消费者侧按吞吐发生
        stubReserve(SeckillOrderService.RESERVE_OK);
        when(publisher.sendOrderRequest(any(), eq(ACTIVITY_ID), eq(USER_ID))).thenReturn(true);

        assertThat(service("mq").buy(ACTIVITY_ID, USER_ID))
                .isEqualTo(new SeckillBuyView(SeckillBuyView.STATUS_QUEUED, null));
        verifyNoInteractions(activityService, orderWriter, orderMapper);
        verify(redis, never()).execute(eq(scripts.compensate), anyList(), any(), any());
    }

    @Test
    void mqModePublishFailureCompensatesAndFails() {
        // 投递不出去就不能把名额留着：否则名额被吃掉、订单又永远不会生成
        stubReserve(SeckillOrderService.RESERVE_OK);
        when(publisher.sendOrderRequest(any(), eq(ACTIVITY_ID), eq(USER_ID))).thenReturn(false);

        assertThatThrownBy(() -> service("mq").buy(ACTIVITY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SYSTEM_ERROR);
        verifyCompensated(ErrorCode.SYSTEM_ERROR);
    }

    // ---------- 异步落单（消费者） ----------

    @Test
    void materializePlacesOrderAndRecordsResult() {
        SeckillActivity activity = activity();
        when(activityService.require(ACTIVITY_ID)).thenReturn(activity);
        when(orderWriter.place(activity, USER_ID)).thenReturn(99L);

        service("mq").materialize(MESSAGE_ID, ACTIVITY_ID, USER_ID);

        verify(hash).put(SeckillLuaScripts.resultKey(ACTIVITY_ID), USER_FIELD,
                SeckillLuaScripts.RESULT_ORDER_PREFIX + 99L);
        verify(redis, never()).execute(eq(scripts.compensate), anyList(), any(), any());
    }

    @Test
    void materializePermanentRejectionCompensatesAndDoesNotRethrow() {
        // DB 库存已尽属永久失败：补偿后正常返回，不触发重投递
        when(activityService.require(ACTIVITY_ID)).thenReturn(activity());
        when(orderWriter.place(any(SeckillActivity.class), eq(USER_ID)))
                .thenThrow(new BusinessException(ErrorCode.SECKILL_SOLD_OUT, "DB 库存已尽"));

        service("mq").materialize(MESSAGE_ID, ACTIVITY_ID, USER_ID);

        verifyCompensated(ErrorCode.SECKILL_SOLD_OUT);
    }

    @Test
    void materializeDuplicateOrderRecordsExistingOrderAndNeverCompensates() {
        // 唯一键冲突说明该用户已有订单：名额正被那张订单占用，归还就等于同一份库存卖两次
        SeckillOrder existing = new SeckillOrder();
        existing.setId(55L);
        existing.setActivityId(ACTIVITY_ID);
        when(activityService.require(ACTIVITY_ID)).thenReturn(activity());
        when(orderWriter.place(any(SeckillActivity.class), eq(USER_ID)))
                .thenThrow(new DuplicateKeyException("uk_seckill_order_user"));
        when(orderMapper.selectOne(any())).thenReturn(existing);

        service("mq").materialize(MESSAGE_ID, ACTIVITY_ID, USER_ID);

        verify(hash).put(SeckillLuaScripts.resultKey(ACTIVITY_ID), USER_FIELD,
                SeckillLuaScripts.RESULT_ORDER_PREFIX + 55L);
        verify(redis, never()).execute(eq(scripts.compensate), anyList(), any(), any());
    }

    @Test
    void materializeTransientFailurePropagatesWithoutCompensation() {
        // 瞬时失败（DB 不可用）必须抛出：切面据此清掉去重守卫，重投递才能重新处理
        when(activityService.require(ACTIVITY_ID)).thenReturn(activity());
        when(orderWriter.place(any(SeckillActivity.class), eq(USER_ID)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service("mq").materialize(MESSAGE_ID, ACTIVITY_ID, USER_ID))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(redis, never()).execute(eq(scripts.compensate), anyList(), any(), any());
    }

    @Test
    void materializeDeclaresDbDedupKeyedByMessageId() throws Exception {
        // 去重守卫是"看不见的"：注解被改掉不会编译失败，只会静默失去幂等。把声明钉住
        // （SpEL 求值本身由 aurora-common 的 IdempotentAspectTest 覆盖）
        Method method = SeckillOrderService.class.getMethod(
                "materialize", String.class, long.class, long.class);
        Idempotent idempotent = method.getAnnotation(Idempotent.class);

        assertThat(idempotent).isNotNull();
        assertThat(idempotent.strategy()).isEqualTo(Strategy.DB_DEDUP);
        assertThat(idempotent.bizType()).isEqualTo(SeckillOrderService.BIZ_TYPE);
        assertThat(idempotent.key())
                .as("去重键必须跟着单次发送的 messageId，否则用户失败后重抢会被挡掉")
                .isEqualTo("#messageId");
    }

    // ---------- 结果查询 ----------

    @Test
    void mineReturnsQueuedWhilePending() {
        stubResultState(SeckillLuaScripts.RESULT_PENDING);

        assertThat(service("mq").mine(ACTIVITY_ID, USER_ID))
                .isEqualTo(new SeckillBuyView(SeckillBuyView.STATUS_QUEUED, null));
    }

    @Test
    void mineReturnsPlacedFromResultHash() {
        stubResultState(SeckillLuaScripts.RESULT_ORDER_PREFIX + 99L);

        assertThat(service("mq").mine(ACTIVITY_ID, USER_ID))
                .isEqualTo(new SeckillBuyView(SeckillBuyView.STATUS_PLACED, 99L));
        verifyNoInteractions(orderMapper);
    }

    @Test
    void mineReportsRecordedFailureReason() {
        stubResultState(SeckillLuaScripts.RESULT_FAIL_PREFIX + ErrorCode.SECKILL_SOLD_OUT.name());

        assertThatThrownBy(() -> service("mq").mine(ACTIVITY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SECKILL_SOLD_OUT);
    }

    @Test
    void mineFallsBackToDbWhenResultHashIsGone() {
        // 结果 hash 只保留到活动结束后一天；订单在 DB 里长期存在，所以必须回落
        SeckillOrder order = new SeckillOrder();
        order.setId(77L);
        when(orderMapper.selectOne(any())).thenReturn(order);

        assertThat(service("mq").mine(ACTIVITY_ID, USER_ID))
                .isEqualTo(new SeckillBuyView(SeckillBuyView.STATUS_PLACED, 77L));
    }

    @Test
    void mineWithoutAnyRecordIsNotFound() {
        assertThatThrownBy(() -> service("mq").mine(ACTIVITY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ---------- 活动维度限流 ----------

    @Test
    void rateLimitedBuyIsRejectedBeforeTheGate() {
        // 限流命中时连 Redis 预扣都不做——这是"最先卸载流量"的检验点
        doReturn(false).when(rateLimiter).tryAcquire(anyString(), anyInt(), any(Duration.class));

        assertThatThrownBy(() -> service("mq").buy(ACTIVITY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
        verifyNoInteractions(redis, activityService, orderWriter, publisher, orderMapper);
    }

    @Test
    void rateLimitIsKeyedPerActivityWithConfiguredQuota() {
        stubReserve(SeckillOrderService.RESERVE_OK);
        when(publisher.sendOrderRequest(any(), eq(ACTIVITY_ID), eq(USER_ID))).thenReturn(true);

        service("mq").buy(ACTIVITY_ID, USER_ID);

        verify(rateLimiter).tryAcquire("seckill:rl:" + ACTIVITY_ID,
                rateLimitProperties.getPerActivityLimit(),
                Duration.ofSeconds(rateLimitProperties.getWindowSeconds()));
    }

    @Test
    void disabledRateLimitSkipsTheLimiter() {
        rateLimitProperties.setEnabled(false);
        stubReserve(SeckillOrderService.RESERVE_OK);
        when(publisher.sendOrderRequest(any(), eq(ACTIVITY_ID), eq(USER_ID))).thenReturn(true);

        service("mq").buy(ACTIVITY_ID, USER_ID);

        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any(Duration.class));
    }

    @Test
    void invalidRateLimitRuleFailsOpen() {
        // 配额/窗口非正数属运维配置失误：放行并告警，不能变成业务的永久 429
        rateLimitProperties.setPerActivityLimit(0);
        stubReserve(SeckillOrderService.RESERVE_OK);
        when(publisher.sendOrderRequest(any(), eq(ACTIVITY_ID), eq(USER_ID))).thenReturn(true);

        assertThat(service("mq").buy(ACTIVITY_ID, USER_ID))
                .isEqualTo(new SeckillBuyView(SeckillBuyView.STATUS_QUEUED, null));
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any(Duration.class));
    }

    // ---------- helpers ----------

    private SeckillOrderService service(String mode) {
        return new SeckillOrderService(activityService, orderWriter, publisher, orderMapper,
                scripts, redis, rateLimiter, rateLimitProperties, mode);
    }

    private void assertReserveCodeRejected(Long reserveResult, ErrorCode expected) {
        stubReserve(reserveResult);
        assertThatThrownBy(() -> service("mq").buy(ACTIVITY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    // doReturn 避开 execute(RedisScript<T>, ...) 的泛型推断问题。
    private void stubReserve(Long result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(), any(), any());
    }

    private void stubResultState(String state) {
        when(hash.get(SeckillLuaScripts.resultKey(ACTIVITY_ID), USER_FIELD)).thenReturn(state);
    }

    private void verifyCompensated(ErrorCode reason) {
        verify(redis).execute(eq(scripts.compensate), anyList(), eq(USER_FIELD), eq(reason.name()));
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
