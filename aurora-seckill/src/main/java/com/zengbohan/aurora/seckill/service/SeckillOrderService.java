package com.zengbohan.aurora.seckill.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.seckill.dto.SeckillBuyView;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import com.zengbohan.aurora.seckill.entity.SeckillOrder;
import com.zengbohan.aurora.seckill.mapper.SeckillOrderMapper;
import com.zengbohan.aurora.seckill.mq.SeckillEventPublisher;
import com.zengbohan.aurora.seckill.ratelimit.SeckillRateLimitProperties;
import com.zengbohan.aurora.seckill.ratelimit.SeckillRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 秒杀下单。落单模式由 {@code aurora.seckill.order-mode} 决定：
 * <ul>
 *   <li>{@code mq}（主链路）：预扣成功后投递 MQ 即返回，请求路径**完全不碰 DB**，
 *       DB 写入由消费者按自己的吞吐节流——这就是削峰；</li>
 *   <li>{@code sync}（对照基线）：请求线程内直接落单，返回时订单已存在。</li>
 * </ul>
 * 两种模式的闸门完全一致：窗口判定、一人一单、库存扣减都在 reserve Lua 里原子完成，
 * 通过闸门的请求数被库存量硬性封顶。
 * <p>
 * 失败补偿：落单永久失败时用 compensate Lua 归还名额（恰好一次，见脚本注释），
 * 同时把结果从 PENDING/PROCESSING 改成 FAIL:{原因}。投递结果不确定时保留 PENDING，
 * 由对账任务确认订单是否已落，再决定记录结果还是归还名额。
 */
@Service
public class SeckillOrderService {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderService.class);

    // 预扣脚本返回码，与 lua/seckill_reserve.lua 头部注释一一对应。
    static final long RESERVE_OK = 1L;
    static final long RESERVE_NOT_PREPARED = -1L;
    static final long RESERVE_NOT_STARTED = 2L;
    static final long RESERVE_ENDED = 3L;
    static final long RESERVE_SOLD_OUT = 4L;
    static final long RESERVE_ALREADY_BOUGHT = 5L;

    private final SeckillActivityService activityService;
    private final SeckillOrderWriter orderWriter;
    private final SeckillEventPublisher publisher;
    private final SeckillOrderMapper orderMapper;
    private final SeckillLuaScripts scripts;
    private final StringRedisTemplate redis;
    private final SeckillRateLimiter rateLimiter;
    private final SeckillRateLimitProperties rateLimitProperties;
    private final OrderMode orderMode;

    public SeckillOrderService(SeckillActivityService activityService, SeckillOrderWriter orderWriter,
                               SeckillEventPublisher publisher, SeckillOrderMapper orderMapper,
                               SeckillLuaScripts scripts, StringRedisTemplate redis,
                               SeckillRateLimiter rateLimiter, SeckillRateLimitProperties rateLimitProperties,
                               @Value("${aurora.seckill.order-mode:mq}") String orderMode) {
        this.activityService = activityService;
        this.orderWriter = orderWriter;
        this.publisher = publisher;
        this.orderMapper = orderMapper;
        this.scripts = scripts;
        this.redis = redis;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
        this.orderMode = OrderMode.parse(orderMode);
    }

    enum OrderMode {
        MQ, SYNC;

        // 配置写错必须启动即失败：静默回退会让"异步"悄悄变成"同步"，实测口径跟着失真
        static OrderMode parse(String raw) {
            for (OrderMode mode : values()) {
                if (mode.name().equalsIgnoreCase(raw)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("unknown aurora.seckill.order-mode: " + raw
                    + " (expected mq or sync)");
        }
    }

    /**
     * 抢购：先过 Redis 闸门，通过后按模式落单。
     * 各拒绝场景抛带业务码的 {@link BusinessException}；MQ 模式下成功返回 QUEUED。
     */
    public SeckillBuyView buy(long activityId, long userId) {
        // 限流在闸门之前：超载的请求连预扣都不做（与网关过滤器的次序一致——最先卸载流量）
        if (!passActivityRateLimit(activityId)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        long reserved = reserve(activityId, userId);
        if (reserved != RESERVE_OK) {
            throw new BusinessException(errorCodeOf(reserved));
        }
        return orderMode == OrderMode.MQ ? publishAsync(activityId, userId) : placeSync(activityId, userId);
    }

    /**
     * 抢购结果查询：Redis 结果状态优先，缺失时回落到 DB。
     * 回落是必要的——结果 hash 的 TTL 只到活动结束后一天，订单在 DB 里则长期存在。
     */
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
        SeckillOrder order = findOrder(activityId, userId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该活动下没有你的抢购记录");
        }
        return new SeckillBuyView(SeckillBuyView.STATUS_PLACED, order.getId());
    }

    /**
     * 异步落单（消费者调用）。
     * <p>
     * 状态机：PENDING -> PROCESSING -> ORDER / FAIL。认领是 Redis 原子操作，
     * 对账任务只补偿 PENDING 或超时的 PROCESSING，因此「归还名额」与「迟到消息写订单」
     * 不会同时发生。DB 去重行与订单写入在同一事务（{@link SeckillOrderWriter#placeOnce}），
     * 订单失败时去重行一起回滚，MQ 重投递不会被永久挡住。
     * <p>
     * 失败语义：永久失败（DB 库存已尽）补偿并正常返回；瞬时失败把状态重置为 PENDING
     * 后抛出，由 MQ 重投递；对账任务兜底处理进程崩溃等不再重投的情况。
     */
    public void materialize(String messageId, long activityId, long userId) {
        if (claim(activityId, userId) != 1L) {
            // ORDER = 已成交；FAIL = 已补偿；结果键不存在 = 没有预扣
            log.info("seckill message skipped (activity={}, user={}, msg={})", activityId, userId, messageId);
            return;
        }
        SeckillActivity activity = null;
        try {
            activity = activityService.require(activityId);
            SeckillOrderWriter.PlaceOutcome outcome = orderWriter.placeOnce(messageId, activity, userId);
            if (outcome.duplicate()) {
                SeckillOrder existing = findOrder(activityId, userId);
                if (existing == null) {
                    // 去重行已提交却没有订单：只可能是历史脏数据，抛出重投递（不补偿，避免超卖）
                    throw new IllegalStateException(
                            "dedup row exists without order (activity=" + activityId + ", user=" + userId + ")");
                }
                recordPlaced(activity, userId, existing.getId());
                log.info("seckill order already existed (activity={}, user={}, order={})",
                        activityId, userId, existing.getId());
                return;
            }
            recordPlaced(activity, userId, outcome.orderId());
            log.info("seckill order materialized (activity={}, user={}, order={})",
                    activityId, userId, outcome.orderId());
        } catch (DuplicateKeyException e) {
            // 订单唯一键兜底：该用户已有订单，名额被那张订单占用，绝不归还
            SeckillOrder existing = findOrder(activityId, userId);
            if (existing == null) {
                log.error("duplicate key but no order row (activity={}, user={})", activityId, userId, e);
                throw e;
            }
            recordPlaced(activity, userId, existing.getId());
            log.info("seckill order already existed (activity={}, user={}, order={})",
                    activityId, userId, existing.getId());
        } catch (BusinessException e) {
            // 永久失败：补偿会把状态从 PROCESSING 改成 FAIL；并发对账先接管时这里返回 0，不重复归还
            long compensated = compensate(activityId, userId, e.getErrorCode());
            log.warn("seckill order rejected permanently (activity={}, user={}, reason={}, compensated={})",
                    activityId, userId, e.getErrorCode(), compensated);
        } catch (RuntimeException e) {
            // 瞬时失败：交还处理权，MQ 重投递或对账任务接手；不能在这里补偿（订单可能已部分写入）
            resetPending(activityId, userId);
            throw e;
        }
    }

    // 同步落单（对照基线）：返回时订单已经存在。
    private SeckillBuyView placeSync(long activityId, long userId) {
        try {
            SeckillActivity activity = activityService.require(activityId);
            long orderId = orderWriter.place(activity, userId);
            recordPlaced(activity, userId, orderId);
            return new SeckillBuyView(SeckillBuyView.STATUS_PLACED, orderId);
        } catch (DuplicateKeyException e) {
            // Redis 标记丢失（重启等）后重抢：唯一键兜底。这次预扣是本调用新扣的、
            // 订单却已存在 → 归还名额（同步路径里预扣必是新鲜的），对调用方报"已参与过"
            compensate(activityId, userId, ErrorCode.SECKILL_ALREADY_BOUGHT);
            throw new BusinessException(ErrorCode.SECKILL_ALREADY_BOUGHT, "该活动下已有订单");
        } catch (RuntimeException e) {
            compensate(activityId, userId, codeOf(e));
            throw e;
        }
    }

    // 异步入队：消息是否被 Broker 接收分为三种结果。
    // 只有「确定没有配置 MQ」才立即补偿；超时等不确定结果保留 PENDING，
    // 由对账任务在确认没有订单后收敛，避免把已送达的消息当成丢失。
    private SeckillBuyView publishAsync(long activityId, long userId) {
        String messageId = UUID.randomUUID().toString();
        SeckillEventPublisher.DispatchResult result =
                publisher.sendOrderRequest(messageId, activityId, userId);
        if (result == SeckillEventPublisher.DispatchResult.NOT_CONFIGURED) {
            compensate(activityId, userId, ErrorCode.SYSTEM_ERROR);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "下单请求未能受理，请稍后重试");
        }
        if (result == SeckillEventPublisher.DispatchResult.UNKNOWN) {
            // 结果未知：不回补名额，也不谎报成功；结果停在 PENDING，对账任务会确认订单或补偿
            log.warn("seckill dispatch result unknown (activity={}, user={}, msg={}); left pending for reconcile",
                    activityId, userId, messageId);
        }
        return new SeckillBuyView(SeckillBuyView.STATUS_QUEUED, null);
    }

    /**
     * 活动维度限流（阈值由配置中心下发，可即时调整）。
     * <p>
     * 规则非法（非正配额/窗口）时**放行**并告警，而不是拒绝：这里与网关过滤器
     * 同一口径——运维配置失误或限流器自身故障（Redis 不可用）都不能放大成业务的
     * 永久 429；真出问题时日志里有明确的 WARN 可查。
     */
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

    // 认领：PENDING/PROCESSING -> PROCESSING；ORDER/FAIL 或结果缺失返回 0。
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

    // 结果写 ORDER:{id}，并把 TTL 重新对齐到"活动结束 + 1 天"（键可能已被回收过）
    private void recordPlaced(SeckillActivity activity, long userId, long orderId) {
        String key = SeckillLuaScripts.resultKey(activity.getId());
        redis.opsForHash().put(key, String.valueOf(userId),
                SeckillLuaScripts.RESULT_ORDER_PREFIX + orderId);
        redis.expire(key, Duration.ofSeconds(resultTtlSeconds(activity.getEndAt())));
    }

    private SeckillOrder findOrder(long activityId, long userId) {
        return orderMapper.selectOne(Wrappers.<SeckillOrder>lambdaQuery()
                .eq(SeckillOrder::getActivityId, activityId)
                .eq(SeckillOrder::getUserId, userId));
    }

    /**
     * 对账：处理停在 PENDING / 超时 PROCESSING 的受理结果。
     * <p>
     * 先查订单：消息其实已落单就补记 ORDER；确认没有订单后才补偿。
     * compensate Lua 会再次检查结果状态，因此与消费者并发时不重复归还。
     *
     * @return 本次修正的条数
     */
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
            // 时间戳缺失（旧格式）按超时处理
            if (since > 0 && now - since < timeout) {
                continue;
            }
            long userId;
            try {
                userId = Long.parseLong(entry.getKey().toString());
            } catch (NumberFormatException e) {
                log.warn("skip malformed seckill result field {} for activity {}", entry.getKey(), activity.getId());
                continue;
            }
            SeckillOrder order = findOrder(activity.getId(), userId);
            if (order != null) {
                recordPlaced(activity, userId, order.getId());
                changed++;
                continue;
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

    // 与预热同口径：结束后仍保留一天，供结果查询与对账
    private static long resultTtlSeconds(LocalDateTime endAt) {
        long residual = Duration.between(LocalDateTime.now(), endAt).toSeconds();
        return Math.max(residual, 0) + Duration.ofDays(1).toSeconds();
    }

    private static ErrorCode codeOf(RuntimeException e) {
        return e instanceof BusinessException business ? business.getErrorCode() : ErrorCode.SYSTEM_ERROR;
    }

    // 存的失败原因是 ErrorCode 名；无法识别时按系统错误返回，旧值/脏值不往外抛 500
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
        // -1 与未知码都按「未就绪」处理：运营没预热、或脚本返回值被改坏，
        // 都不该被误判成"售罄"（那会把运营引到错误的方向）
        return ErrorCode.SECKILL_NOT_READY;
    }
}
