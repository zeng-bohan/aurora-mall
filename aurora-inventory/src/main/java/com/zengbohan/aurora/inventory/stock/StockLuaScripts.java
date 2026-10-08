package com.zengbohan.aurora.inventory.stock;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

// 一次性加载库存 Lua 脚本；全部都是原子操作。
@Component
public class StockLuaScripts {

    // 释放标记 TTL：7 天，覆盖关单补偿的所有重试窗口。
    public static final String RELEASE_MARKER_TTL_SECONDS = "604800";

    public final DefaultRedisScript<Long> reserve;
    public final DefaultRedisScript<Long> rollback;

    public StockLuaScripts() {
        reserve = new DefaultRedisScript<>();
        reserve.setLocation(new ClassPathResource("lua/stock_reserve.lua"));
        reserve.setResultType(Long.class);
        rollback = new DefaultRedisScript<>();
        rollback.setLocation(new ClassPathResource("lua/stock_rollback.lua"));
        rollback.setResultType(Long.class);
    }

    public static String key(long skuId) {
        return "aurora:stock:" + skuId;
    }

    // 回滚恰好一次的标记 key（KEYS[1] of rollback script）。
    public static String releasedMarkerKey(long orderId) {
        return "aurora:stock:released:" + orderId;
    }
}
