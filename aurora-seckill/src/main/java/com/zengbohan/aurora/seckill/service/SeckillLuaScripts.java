package com.zengbohan.aurora.seckill.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

// 一次性加载秒杀 Lua 脚本；两个脚本各自都是原子的（返回值见脚本头部注释）。
@Component
public class SeckillLuaScripts {

    /** 预扣：窗口判定 + 一人一单 + 库存扣减。 */
    public final DefaultRedisScript<Long> reserve;
    /** 预扣补偿：DB 落单失败时归还，靠已购标记保证恰好一次。 */
    public final DefaultRedisScript<Long> compensate;

    public SeckillLuaScripts() {
        reserve = scriptAt("lua/seckill_reserve.lua");
        compensate = scriptAt("lua/seckill_compensate.lua");
    }

    public static String activityKey(long activityId) {
        return SeckillPreheatService.ACTIVITY_KEY_PREFIX + activityId;
    }

    // 一人一单标记集合（成员是 userId）：预扣时加入、补偿时移除，同时充当补偿的幂等凭据。
    public static String boughtKey(long activityId) {
        return "seckill:bought:" + activityId;
    }

    private static DefaultRedisScript<Long> scriptAt(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }
}
