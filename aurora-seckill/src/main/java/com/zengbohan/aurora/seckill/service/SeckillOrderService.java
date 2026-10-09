package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 秒杀下单（S2-1：同步落单版本；S2-2 会把「落单」换成 MQ 异步，预扣层不变）。
 * <p>
 * 顺序是刻意的：**先过 Redis 闸门**——窗口判定、一人一单、库存扣减都在一个 Lua 里原子完成，
 * 只有拿到预扣名额的少数请求才去读活动、写 DB。通过闸门的请求数被库存量硬性封顶，
 * 峰值流量不会直接淹到 DB。
 * <p>
 * 失败补偿：DB 落单或扣减失败时把预扣还回去（{@code seckill_compensate.lua}），
 * 凭「已购标记」保证恰好一次——重复补偿不会把库存加第二次。
 */
@Service
public class SeckillOrderService {

    // 预扣脚本返回码，与 lua/seckill_reserve.lua 头部注释一一对应。
    static final long RESERVE_OK = 1L;
    static final long RESERVE_NOT_PREPARED = -1L;
    static final long RESERVE_NOT_STARTED = 2L;
    static final long RESERVE_ENDED = 3L;
    static final long RESERVE_SOLD_OUT = 4L;
    static final long RESERVE_ALREADY_BOUGHT = 5L;

    private final SeckillActivityService activityService;
    private final SeckillOrderWriter orderWriter;
    private final SeckillLuaScripts scripts;
    private final StringRedisTemplate redis;

    public SeckillOrderService(SeckillActivityService activityService, SeckillOrderWriter orderWriter,
                               SeckillLuaScripts scripts, StringRedisTemplate redis) {
        this.activityService = activityService;
        this.orderWriter = orderWriter;
        this.scripts = scripts;
        this.redis = redis;
    }

    /**
     * 下单：成功返回秒杀订单 id；各类拒绝抛带业务码的 {@link BusinessException}。
     * 活动不存在由 {@link SeckillActivityService#require} 抛 NOT_FOUND（只发生在拿到名额之后）。
     */
    public long buy(long activityId, long userId) {
        long reserved = reserve(activityId, userId);
        if (reserved != RESERVE_OK) {
            throw new BusinessException(errorCodeOf(reserved));
        }
        try {
            SeckillActivity activity = activityService.require(activityId);
            return orderWriter.place(activity, userId);
        } catch (RuntimeException e) {
            // 落单没成：预扣必须还回去，否则名额被永久吃掉
            compensate(activityId, userId);
            if (e instanceof DuplicateKeyException) {
                // Redis 标记丢失（重启等）时由 DB 唯一键兜底，对调用方语义仍是"已参与过"
                throw new BusinessException(ErrorCode.SECKILL_ALREADY_BOUGHT, "该活动下已有订单");
            }
            throw e;
        }
    }

    private long reserve(long activityId, long userId) {
        Long result = redis.execute(scripts.reserve, keys(activityId),
                String.valueOf(userId), String.valueOf(System.currentTimeMillis()));
        return result == null ? RESERVE_NOT_PREPARED : result;
    }

    private void compensate(long activityId, long userId) {
        redis.execute(scripts.compensate, keys(activityId), String.valueOf(userId));
    }

    private static List<String> keys(long activityId) {
        return List.of(SeckillLuaScripts.activityKey(activityId), SeckillLuaScripts.boughtKey(activityId));
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
