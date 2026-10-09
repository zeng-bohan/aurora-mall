package com.zengbohan.aurora.seckill.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.seckill.entity.SeckillActivity;
import com.zengbohan.aurora.seckill.entity.SeckillStock;
import com.zengbohan.aurora.seckill.mapper.SeckillStockMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 库存预热：把 DB 的活动/库存写进 Redis，作为 S2 预扣（Lua）的快照与判定来源。
 * <p>
 * 预热是**幂等覆盖**：Redis 的余量永远重置为 DB 的 available（不是累加）——
 * DB 才是事实，重复预热只会让快照更接近事实。
 * <p>
 * 键布局（S2 的 Lua 按此读取，字段缺失即视为活动未就绪）：
 * <pre>seckill:activity:{id}  hash: startAt / endAt / perUserLimit / totalStock / stock</pre>
 * 时间为 epoch millis；TTL = 活动结束 + 1 天（过期自清理，窗口外 Lua 直接拒绝）。
 */
@Service
public class SeckillPreheatService {

    static final String ACTIVITY_KEY_PREFIX = "seckill:activity:";
    static final String FIELD_START_AT = "startAt";
    static final String FIELD_END_AT = "endAt";
    static final String FIELD_PER_USER_LIMIT = "perUserLimit";
    static final String FIELD_TOTAL_STOCK = "totalStock";
    static final String FIELD_STOCK = "stock";

    private final SeckillActivityService activityService;
    private final SeckillStockMapper stockMapper;
    private final StringRedisTemplate redis;

    public SeckillPreheatService(SeckillActivityService activityService,
                                 SeckillStockMapper stockMapper, StringRedisTemplate redis) {
        this.activityService = activityService;
        this.stockMapper = stockMapper;
        this.redis = redis;
    }

    /**
     * 预热指定活动。已结束的活动拒绝（没有意义，S2 的 Lua 在窗口外同样拒绝）。
     */
    public PreheatResult preheat(long activityId) {
        SeckillActivity activity = activityService.require(activityId);
        if (SeckillPhase.of(activity.getStartAt(), activity.getEndAt(), now())
                == SeckillPhase.ENDED) {
            throw new BusinessException(ErrorCode.SECKILL_ENDED, "活动已结束，无需预热");
        }
        SeckillStock stock = stockMapper.selectById(activityId);
        int available = stock == null ? 0 : stock.getAvailable();

        String key = ACTIVITY_KEY_PREFIX + activityId;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_START_AT, String.valueOf(toEpochMilli(activity.getStartAt())));
        fields.put(FIELD_END_AT, String.valueOf(toEpochMilli(activity.getEndAt())));
        fields.put(FIELD_PER_USER_LIMIT, String.valueOf(activity.getPerUserLimit()));
        fields.put(FIELD_TOTAL_STOCK, String.valueOf(activity.getTotalStock()));
        fields.put(FIELD_STOCK, String.valueOf(available));
        redis.opsForHash().putAll(key, fields);
        // 结束后保留一天供对账查询，之后由 Redis 回收（窗口外 Lua 也会拒绝）
        redis.expire(key, Duration.ofSeconds(residualSeconds(activity.getEndAt())
                + Duration.ofDays(1).toSeconds()));
        return new PreheatResult(activityId, available);
    }

    public record PreheatResult(long activityId, int stock) {
    }

    // 结束前剩余秒数（已结束为 0：TTL 至少再挂一天，避免立即回收）。
    private long residualSeconds(LocalDateTime endAt) {
        long residual = Duration.between(now(), endAt).toSeconds();
        return Math.max(residual, 0);
    }

    private long toEpochMilli(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    // 当前时间（测试钩子：匿名子类固定时间）。
    LocalDateTime now() {
        return LocalDateTime.now();
    }
}
