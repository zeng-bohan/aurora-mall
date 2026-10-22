package com.zengbohan.aurora.inventory.stock;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class StockLuaScripts {

    public static final String RESERVE_GUARD_TTL_SECONDS = "604800";

    public final DefaultRedisScript<Long> reserve;
    public final DefaultRedisScript<Long> release;
    public final DefaultRedisScript<Long> restore;
    public final DefaultRedisScript<Long> setIfUnchanged;

    public StockLuaScripts() {
        reserve = script("lua/stock_batch_reserve.lua");
        release = script("lua/stock_batch_release.lua");
        restore = script("lua/stock_batch_restore.lua");
        setIfUnchanged = script("lua/stock_set.lua");
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    public static String key(long skuId) {
        return "aurora:stock:" + skuId;
    }

    public static String guardKey(long tradeId) {
        return "aurora:stock:reserve-guard:" + tradeId;
    }
}
