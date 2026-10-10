package com.zengbohan.aurora.inventory.stock;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

// 一次性加载库存 Lua 脚本；全部都是原子操作。
@Component
public class StockLuaScripts {

    // 释放标记 TTL：7 天，覆盖关单补偿的所有重试窗口。
    public static final String RELEASE_MARKER_TTL_SECONDS = "604800";

    // 预扣守卫 TTL：同样 7 天，覆盖同一订单的重试/重投递/响应丢失后的重放窗口。
    public static final String RESERVE_GUARD_TTL_SECONDS = "604800";

    public final DefaultRedisScript<Long> reserve;
    public final DefaultRedisScript<Long> rollback;
    /** 结果未知的预扣补偿：只在预扣守卫存在时回补（见 lua 注释）。 */
    public final DefaultRedisScript<Long> compensateReserve;

    public StockLuaScripts() {
        reserve = new DefaultRedisScript<>();
        reserve.setLocation(new ClassPathResource("lua/stock_reserve.lua"));
        reserve.setResultType(Long.class);
        rollback = new DefaultRedisScript<>();
        rollback.setLocation(new ClassPathResource("lua/stock_rollback.lua"));
        rollback.setResultType(Long.class);
        compensateReserve = new DefaultRedisScript<>();
        compensateReserve.setLocation(new ClassPathResource("lua/stock_compensate_reserve.lua"));
        compensateReserve.setResultType(Long.class);
    }

    public static String key(long skuId) {
        return "aurora:stock:" + skuId;
    }

    // 回滚恰好一次的标记 key（KEYS[1] of rollback script）。
    public static String releasedMarkerKey(long orderId) {
        return "aurora:stock:released:" + orderId;
    }

    // 预扣恰好一次的守卫 key（KEYS[2] of reserve script）：同一订单的重复预扣被挡在扣减之前。
    public static String reserveGuardKey(long orderId) {
        return "aurora:stock:reserve-guard:" + orderId;
    }
}
