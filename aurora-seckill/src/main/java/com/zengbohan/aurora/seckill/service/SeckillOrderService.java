package com.zengbohan.aurora.seckill.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.common.idempotent.Idempotent;
import com.zengbohan.aurora.common.idempotent.Strategy;
import com.zengbohan.aurora.seckill.dto.SeckillBuyView;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import com.zengbohan.aurora.seckill.entity.SeckillOrder;
import com.zengbohan.aurora.seckill.mapper.SeckillOrderMapper;
import com.zengbohan.aurora.seckill.mq.SeckillEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
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
 * 失败补偿：落单或投递失败时用 compensate Lua 归还名额（恰好一次，见脚本注释），
 * 同时把结果从 PENDING 改成 FAIL:{原因}，客户端才不会一直等下去。
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

    static final String BIZ_TYPE = "seckill-order";

    private final SeckillActivityService activityService;
    private final SeckillOrderWriter orderWriter;
    private final SeckillEventPublisher publisher;
    private final SeckillOrderMapper orderMapper;
    private final SeckillLuaScripts scripts;
    private final StringRedisTemplate redis;
    private final OrderMode orderMode;

    public SeckillOrderService(SeckillActivityService activityService, SeckillOrderWriter orderWriter,
                               SeckillEventPublisher publisher, SeckillOrderMapper orderMapper,
                               SeckillLuaScripts scripts, StringRedisTemplate redis,
                               @Value("${aurora.seckill.order-mode:mq}") String orderMode) {
        this.activityService = activityService;
        this.orderWriter = orderWriter;
        this.publisher = publisher;
        this.orderMapper = orderMapper;
        this.scripts = scripts;
        this.redis = redis;
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
            if (SeckillLuaScripts.RESULT_PENDING.equals(state)) {
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
     * 幂等有两层，缺一不可：
     * <ol>
     *   <li>本方法上的 DB_DEDUP 守卫挡住同一条消息的重投递（key = 发送时生成的 messageId）；</li>
     *   <li>方法本身可重复执行：落单受唯一键约束、补偿恰好一次、结果写入是覆盖写。</li>
     * </ol>
     * 失败语义刻意区分：<b>永久失败</b>（典型是 DB 库存已尽）在这里补偿并正常返回，
     * 不触发重投递；<b>瞬时失败</b>（DB 不可用等）直接抛出——切面会清掉守卫让重投递
     * 重新处理，而守卫在，重复投递不会造成二次补偿。
     */
    @Idempotent(strategy = Strategy.DB_DEDUP, bizType = BIZ_TYPE, key = "#messageId")
    public void materialize(String messageId, long activityId, long userId) {
        SeckillActivity activity = null;
        try {
            activity = activityService.require(activityId);
            long orderId = orderWriter.place(activity, userId);
            recordPlaced(activity, userId, orderId);
            log.info("seckill order materialized (activity={}, user={}, order={})", activityId, userId, orderId);
        } catch (DuplicateKeyException e) {
            // 订单已存在：生产者重发、或补偿后重抢而订单其实已落。名额已被那张订单占用，
            // 绝不归还——还回去等于同一份库存卖两次。宁可少卖一个名额，也不冒超卖风险。
            // （activity 在此必然非 null：唯一键冲突只能来自 require 成功之后的 place）
            SeckillOrder existing = findOrder(activityId, userId);
            if (existing == null) {
                // 唯一键冲突却查不到行，不该发生：抛出重投递（不补偿，避免超卖）
                log.error("duplicate key but no order row (activity={}, user={})", activityId, userId, e);
                throw e;
            }
            recordPlaced(activity, userId, existing.getId());
            log.info("seckill order already existed (activity={}, user={}, order={})",
                    activityId, userId, existing.getId());
        } catch (BusinessException e) {
            // 永久失败（DB 库存已尽 / 活动已不存在）：归还名额并记录原因后结束这条消息。
            // 用户若重新抢购，那是新的预扣 + 新 messageId，不会被去重挡住。
            compensate(activityId, userId, e.getErrorCode());
            log.warn("seckill order rejected permanently (activity={}, user={}): {}",
                    activityId, userId, e.getErrorCode());
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

    // 异步入队：投递不出去就必须归还名额，否则名额被吃掉、订单又永远不会生成。
    private SeckillBuyView publishAsync(long activityId, long userId) {
        String messageId = UUID.randomUUID().toString();
        if (!publisher.sendOrderRequest(messageId, activityId, userId)) {
            compensate(activityId, userId, ErrorCode.SYSTEM_ERROR);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "下单请求未能受理，请稍后重试");
        }
        return new SeckillBuyView(SeckillBuyView.STATUS_QUEUED, null);
    }

    private long reserve(long activityId, long userId) {
        Long result = redis.execute(scripts.reserve, SeckillLuaScripts.keys(activityId),
                String.valueOf(userId), String.valueOf(System.currentTimeMillis()));
        return result == null ? RESERVE_NOT_PREPARED : result;
    }

    private void compensate(long activityId, long userId, ErrorCode reason) {
        redis.execute(scripts.compensate, SeckillLuaScripts.keys(activityId),
                String.valueOf(userId), reason.name());
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
