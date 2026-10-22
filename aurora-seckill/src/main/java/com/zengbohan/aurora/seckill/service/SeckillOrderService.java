package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.result.RemoteCall;
import com.zengbohan.aurora.common.result.Result;
import com.zengbohan.aurora.seckill.client.OrderClient;
import com.zengbohan.aurora.seckill.dto.SeckillBuyView;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import com.zengbohan.aurora.seckill.entity.SeckillReservation;
import com.zengbohan.aurora.seckill.mapper.SeckillReleaseMapper;
import com.zengbohan.aurora.seckill.mapper.SeckillReservationMapper;
import com.zengbohan.aurora.seckill.mapper.SeckillStockMapper;
import com.zengbohan.aurora.seckill.mq.SeckillEventPublisher;
import com.zengbohan.aurora.seckill.ratelimit.SeckillRateLimitProperties;
import com.zengbohan.aurora.seckill.ratelimit.SeckillRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class SeckillOrderService {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderService.class);

    static final long RESERVE_OK = 1L;
    static final long RESERVE_NOT_PREPARED = -1L;
    static final long RESERVE_NOT_STARTED = 2L;
    static final long RESERVE_ENDED = 3L;
    static final long RESERVE_SOLD_OUT = 4L;
    static final long RESERVE_ALREADY_BOUGHT = 5L;

    private final SeckillActivityService activityService;
    private final SeckillReservationMapper reservationMapper;
    private final SeckillStockMapper stockMapper;
    private final SeckillReleaseMapper releaseMapper;
    private final SeckillEventPublisher publisher;
    private final SeckillLuaScripts scripts;
    private final StringRedisTemplate redis;
    private final SeckillRateLimiter rateLimiter;
    private final SeckillRateLimitProperties rateLimitProperties;
    private final OrderClient orderClient;
    private final TransactionTemplate transactionTemplate;
    private final OrderMode orderMode;

    public SeckillOrderService(SeckillActivityService activityService,
                               SeckillReservationMapper reservationMapper,
                               SeckillStockMapper stockMapper,
                               SeckillReleaseMapper releaseMapper,
                               SeckillEventPublisher publisher,
                               SeckillLuaScripts scripts, StringRedisTemplate redis,
                               SeckillRateLimiter rateLimiter,
                               SeckillRateLimitProperties rateLimitProperties,
                               OrderClient orderClient,
                               TransactionTemplate transactionTemplate,
                               @Value("${aurora.seckill.order-mode:mq}") String orderMode) {
        this.activityService = activityService;
        this.reservationMapper = reservationMapper;
        this.stockMapper = stockMapper;
        this.releaseMapper = releaseMapper;
        this.publisher = publisher;
        this.scripts = scripts;
        this.redis = redis;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
        this.orderClient = orderClient;
        this.transactionTemplate = transactionTemplate;
        this.orderMode = OrderMode.parse(orderMode);
    }

    enum OrderMode {
        MQ, SYNC;

        static OrderMode parse(String raw) {
            for (OrderMode mode : values()) {
                if (mode.name().equalsIgnoreCase(raw)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("unknown aurora.seckill.order-mode: " + raw);
        }
    }

    public SeckillBuyView buy(long activityId, long userId) {
        if (!passActivityRateLimit(activityId)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        long reserved = reserve(activityId, userId);
        if (reserved != RESERVE_OK) {
            throw new BusinessException(errorCodeOf(reserved));
        }
        return orderMode == OrderMode.MQ ? publishAsync(activityId, userId) : placeSync(activityId, userId);
    }

    public SeckillBuyView mine(long activityId, long userId) {
        Object value = redis.opsForHash().get(SeckillLuaScripts.resultKey(activityId), String.valueOf(userId));
        String state = value == null ? null : value.toString();
        if (state != null) {
            if (state.startsWith(SeckillLuaScripts.RESULT_ORDER_PREFIX)) {
                return new SeckillBuyView(SeckillBuyView.STATUS_PLACED,
                        Long.parseLong(state.substring(SeckillLuaScripts.RESULT_ORDER_PREFIX.length())));
            }
            if (SeckillLuaScripts.isPending(state) || SeckillLuaScripts.isProcessing(state)) {
                return new SeckillBuyView(SeckillBuyView.STATUS_QUEUED, null);
            }
            if (state.startsWith(SeckillLuaScripts.RESULT_FAIL_PREFIX)) {
                throw new BusinessException(
                        failCodeOf(state.substring(SeckillLuaScripts.RESULT_FAIL_PREFIX.length())));
            }
        }
        Long tradeId = findOrder(activityId, userId);
        if (tradeId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该活动下没有你的抢购记录");
        }
        return new SeckillBuyView(SeckillBuyView.STATUS_PLACED, tradeId);
    }

    public void materialize(String messageId, long activityId, long userId) {
        if (claim(activityId, userId) != 1L) {
            log.info("seckill message skipped (activity={}, user={}, msg={})", activityId, userId, messageId);
            return;
        }
        SeckillActivity activity = activityService.require(activityId);
        try {
            Long existing = findOrder(activityId, userId);
            if (existing != null) {
                consumeReservation(activityId, userId, existing);
                recordPlaced(activity, userId, existing);
                return;
            }
            ensureReservation(activityId, userId);
            long tradeId = placeOrder(activity, userId, messageId);
            consumeReservation(activityId, userId, tradeId);
            recordPlaced(activity, userId, tradeId);
            log.info("seckill trade created (activity={}, user={}, trade={})", activityId, userId, tradeId);
        } catch (BusinessException e) {
            Long existing = safeFindOrder(activityId, userId);
            if (existing != null) {
                consumeReservation(activityId, userId, existing);
                recordPlaced(activity, userId, existing);
                return;
            }
            releaseDbReservation(activityId, userId);
            long compensated = compensate(activityId, userId, e.getErrorCode());
            log.warn("seckill rejected (activity={}, user={}, reason={}, compensated={})",
                    activityId, userId, e.getErrorCode(), compensated);
        } catch (RuntimeException e) {
            Long existing = safeFindOrder(activityId, userId);
            if (existing != null) {
                consumeReservation(activityId, userId, existing);
                recordPlaced(activity, userId, existing);
                return;
            }
            releaseDbReservation(activityId, userId);
            resetPending(activityId, userId);
            throw e;
        }
    }

    public boolean releaseForTrade(long tradeId, long activityId, int quantity, String reason) {
        int quantitySafe = Math.max(quantity, 1);
        boolean[] newlyReleased = {false};
        boolean[] releaseRecorded = {false};
        transactionTemplate.executeWithoutResult(status -> {
            if (releaseMapper.insertIfAbsent(tradeId, activityId, quantitySafe, reason) == 1) {
                if (stockMapper.restore(activityId, quantitySafe) == 0) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "秒杀活动库存不存在");
                }
                reservationMapper.markReleasedByTrade(tradeId);
                newlyReleased[0] = true;
                releaseRecorded[0] = true;
            } else {
                releaseRecorded[0] = releaseMapper.exists(tradeId) > 0;
            }
        });
        if (!releaseRecorded[0]) {
            return false;
        }
        // DB 已记归还；Redis 同步用订单级守卫保证恰好一次。失败时抛出让订单关单/
        // 退款结算重试，下一次会继续补偿而不是把差额留在 Redis。
        Long applied;
        try {
            applied = redis.execute(scripts.releaseStock,
                    java.util.List.of(SeckillLuaScripts.activityKey(activityId),
                            SeckillLuaScripts.releaseGuardKey(tradeId)),
                    String.valueOf(tradeId), String.valueOf(quantitySafe), "2592000");
        } catch (RuntimeException e) {
            log.error("seckill redis stock restore failed for trade {}", tradeId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "秒杀库存 Redis 同步失败，等待重试");
        }
        if (applied == null || (applied != 1L && applied != 0L && applied != -3L)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "秒杀库存 Redis 同步失败，等待重试");
        }
        return newlyReleased[0];
    }

    private SeckillBuyView placeSync(long activityId, long userId) {
        SeckillActivity activity = activityService.require(activityId);
        try {
            Long existing = findOrder(activityId, userId);
            if (existing != null) {
                consumeReservation(activityId, userId, existing);
                recordPlaced(activity, userId, existing);
                return new SeckillBuyView(SeckillBuyView.STATUS_PLACED, existing);
            }
            ensureReservation(activityId, userId);
            long tradeId = placeOrder(activity, userId, "sync-" + UUID.randomUUID());
            consumeReservation(activityId, userId, tradeId);
            recordPlaced(activity, userId, tradeId);
            return new SeckillBuyView(SeckillBuyView.STATUS_PLACED, tradeId);
        } catch (RuntimeException e) {
            Long existing = safeFindOrder(activityId, userId);
            if (existing != null) {
                consumeReservation(activityId, userId, existing);
                recordPlaced(activity, userId, existing);
                return new SeckillBuyView(SeckillBuyView.STATUS_PLACED, existing);
            }
            releaseDbReservation(activityId, userId);
            compensate(activityId, userId, codeOf(e));
            throw e;
        }
    }

    private void ensureReservation(long activityId, long userId) {
        SeckillReservation reservation = reservationMapper.find(activityId, userId);
        if (reservation == null) {
            transactionTemplate.executeWithoutResult(status -> {
                if (reservationMapper.insertIfAbsent(activityId, userId) == 1) {
                    if (stockMapper.deduct(activityId, 1) == 0) {
                        throw new BusinessException(ErrorCode.SECKILL_SOLD_OUT);
                    }
                }
            });
            reservation = reservationMapper.find(activityId, userId);
        }
        if (reservation == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "秒杀预约台账写入失败");
        }
        if ("RELEASED".equals(reservation.getStatus())) {
            throw new BusinessException(ErrorCode.SECKILL_ALREADY_BOUGHT);
        }
        if ("CONSUMED".equals(reservation.getStatus()) && reservation.getTradeId() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "秒杀预约状态异常");
        }
    }

    private void releaseDbReservation(long activityId, long userId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (reservationMapper.markReleased(activityId, userId) == 1) {
                    stockMapper.restore(activityId, 1);
                }
            });
        } catch (RuntimeException e) {
            log.error("seckill db reservation release failed (activity={}, user={})", activityId, userId, e);
        }
    }

    private void consumeReservation(long activityId, long userId, long tradeId) {
        SeckillReservation reservation = reservationMapper.find(activityId, userId);
        if (reservation != null && "RESERVED".equals(reservation.getStatus())) {
            reservationMapper.markConsumed(activityId, userId, tradeId);
        }
    }

    private long placeOrder(SeckillActivity activity, long userId, String messageId) {
        Result<Long> result = RemoteCall.invoke("订单", "秒杀活动 " + activity.getId(),
                () -> orderClient.place(new OrderClient.PlaceSeckillRequest(
                        userId, activity.getId(), activity.getSkuId(),
                        activity.getSeckillPrice(), messageId)));
        return RemoteCall.data(result, "订单");
    }

    private Long findOrder(long activityId, long userId) {
        Result<Long> result = RemoteCall.invoke("订单", "秒杀结果 " + activityId,
                () -> orderClient.find(activityId, userId));
        if (result.code() == ErrorCode.NOT_FOUND.getCode()) {
            return null;
        }
        return RemoteCall.data(result, "订单");
    }

    private Long safeFindOrder(long activityId, long userId) {
        try {
            return findOrder(activityId, userId);
        } catch (RuntimeException e) {
            log.warn("order lookup failed for seckill (activity={}, user={})", activityId, userId);
            return null;
        }
    }

    private SeckillBuyView publishAsync(long activityId, long userId) {
        String messageId = UUID.randomUUID().toString();
        SeckillEventPublisher.DispatchResult result =
                publisher.sendOrderRequest(messageId, activityId, userId);
        if (result == SeckillEventPublisher.DispatchResult.NOT_CONFIGURED) {
            compensate(activityId, userId, ErrorCode.SYSTEM_ERROR);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "下单请求未能受理，请稍后重试");
        }
        if (result == SeckillEventPublisher.DispatchResult.UNKNOWN) {
            log.warn("seckill dispatch result unknown (activity={}, user={}, msg={})",
                    activityId, userId, messageId);
        }
        return new SeckillBuyView(SeckillBuyView.STATUS_QUEUED, null);
    }

    private boolean passActivityRateLimit(long activityId) {
        if (!rateLimitProperties.isEnabled()) {
            return true;
        }
        int limit = rateLimitProperties.getPerActivityLimit();
        int windowSeconds = rateLimitProperties.getWindowSeconds();
        if (limit <= 0 || windowSeconds <= 0) {
            log.warn("invalid seckill rate-limit rule (per-activity-limit={}, window-seconds={}); allowing request",
                    limit, windowSeconds);
            return true;
        }
        return rateLimiter.tryAcquire(SeckillRateLimiter.activityKey(activityId),
                limit, Duration.ofSeconds(windowSeconds));
    }

    private long reserve(long activityId, long userId) {
        Long result = redis.execute(scripts.reserve, SeckillLuaScripts.keys(activityId),
                String.valueOf(userId), String.valueOf(System.currentTimeMillis()));
        return result == null ? RESERVE_NOT_PREPARED : result;
    }

    private long claim(long activityId, long userId) {
        Long result = redis.execute(scripts.claim, SeckillLuaScripts.keys(activityId),
                String.valueOf(userId), String.valueOf(System.currentTimeMillis()));
        return result == null ? 0L : result;
    }

    private void resetPending(long activityId, long userId) {
        redis.execute(scripts.resetPending, SeckillLuaScripts.keys(activityId),
                String.valueOf(userId), String.valueOf(System.currentTimeMillis()));
    }

    private long compensate(long activityId, long userId, ErrorCode reason) {
        Long result = redis.execute(scripts.compensate, SeckillLuaScripts.keys(activityId),
                String.valueOf(userId), reason.name());
        return result == null ? 0L : result;
    }

    private void recordPlaced(SeckillActivity activity, long userId, long tradeId) {
        String key = SeckillLuaScripts.resultKey(activity.getId());
        redis.opsForHash().put(key, String.valueOf(userId),
                SeckillLuaScripts.RESULT_ORDER_PREFIX + tradeId);
        redis.expire(key, Duration.ofSeconds(resultTtlSeconds(activity.getEndAt())));
    }

    public int reconcile(SeckillActivity activity, long pendingTimeoutMillis, long processingTimeoutMillis) {
        Map<Object, Object> entries = redis.opsForHash().entries(SeckillLuaScripts.resultKey(activity.getId()));
        if (entries.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int changed = 0;
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String state = entry.getValue() == null ? null : entry.getValue().toString();
            boolean pending = SeckillLuaScripts.isPending(state);
            boolean processing = SeckillLuaScripts.isProcessing(state);
            if (!pending && !processing) {
                continue;
            }
            long since = SeckillLuaScripts.millisOf(state);
            long timeout = pending ? pendingTimeoutMillis : processingTimeoutMillis;
            if (since > 0 && now - since < timeout) {
                continue;
            }
            long userId;
            try {
                userId = Long.parseLong(entry.getKey().toString());
            } catch (NumberFormatException e) {
                log.warn("skip malformed seckill result field {} for activity {}",
                        entry.getKey(), activity.getId());
                continue;
            }
            Long tradeId;
            try {
                tradeId = findOrder(activity.getId(), userId);
            } catch (RuntimeException e) {
                log.warn("seckill reconcile skips activity {} user {}: order service unavailable",
                        activity.getId(), userId);
                continue;
            }
            if (tradeId != null) {
                consumeReservation(activity.getId(), userId, tradeId);
                recordPlaced(activity, userId, tradeId);
                changed++;
                continue;
            }
            SeckillReservation reservation = reservationMapper.find(activity.getId(), userId);
            if (reservation != null && "CONSUMED".equals(reservation.getStatus())) {
                log.warn("seckill reservation consumed without trade (activity={}, user={})",
                        activity.getId(), userId);
                continue;
            }
            if (reservation != null && "RESERVED".equals(reservation.getStatus())) {
                releaseDbReservation(activity.getId(), userId);
            }
            long compensated = compensate(activity.getId(), userId, ErrorCode.SYSTEM_ERROR);
            if (compensated == 1L) {
                changed++;
                log.warn("reconciled stale seckill reservation (activity={}, user={}, state={})",
                        activity.getId(), userId, state);
            }
        }
        return changed;
    }

    private static long resultTtlSeconds(LocalDateTime endAt) {
        long residual = Duration.between(LocalDateTime.now(), endAt).toSeconds();
        return Math.max(residual, 0) + Duration.ofDays(1).toSeconds();
    }

    private static ErrorCode codeOf(RuntimeException e) {
        return e instanceof BusinessException business ? business.getErrorCode() : ErrorCode.SYSTEM_ERROR;
    }

    private static ErrorCode failCodeOf(String name) {
        try {
            return ErrorCode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ErrorCode.SYSTEM_ERROR;
        }
    }

    private static ErrorCode errorCodeOf(long reserved) {
        if (reserved == RESERVE_NOT_STARTED) {
            return ErrorCode.SECKILL_NOT_STARTED;
        }
        if (reserved == RESERVE_ENDED) {
            return ErrorCode.SECKILL_ENDED;
        }
        if (reserved == RESERVE_SOLD_OUT) {
            return ErrorCode.SECKILL_SOLD_OUT;
        }
        if (reserved == RESERVE_ALREADY_BOUGHT) {
            return ErrorCode.SECKILL_ALREADY_BOUGHT;
        }
        return ErrorCode.SECKILL_NOT_READY;
    }
}
