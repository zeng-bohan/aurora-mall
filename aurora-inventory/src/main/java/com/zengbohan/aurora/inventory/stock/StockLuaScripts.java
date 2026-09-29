package com.zengbohan.aurora.inventory.stock;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Loads the stock Lua scripts once; both are atomic single-key operations. */
@Component
public class StockLuaScripts {

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
}
