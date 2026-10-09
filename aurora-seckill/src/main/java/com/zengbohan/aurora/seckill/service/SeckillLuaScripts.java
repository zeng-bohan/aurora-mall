package com.zengbohan.aurora.seckill.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

// 一次性加载秒杀 Lua 脚本；两个脚本各自都是原子的（返回值见脚本头部注释）。
@Component
public class SeckillLuaScripts {

    // 结果 hash 的值词汇表。字面量同时出现在 lua 里（脚本内无法引用 Java 常量），
    // 改动必须两边一起改——这里集中定义是为了让 Java 侧只有一个事实源。
    /** 已受理、等待落单。 */
    public static final String RESULT_PENDING = "PENDING";
    /** 已落单：ORDER:{orderId}。 */
    public static final String RESULT_ORDER_PREFIX = "ORDER:";
    /** 永久失败（名额已归还）：FAIL:{ErrorCode 名}。 */
    public static final String RESULT_FAIL_PREFIX = "FAIL:";

    /** 预扣：窗口判定 + 一人一单 + 库存扣减 + 受理标记。 */
    public final DefaultRedisScript<Long> reserve;
    /** 预扣补偿：归还名额并把结果改成失败原因，靠已购标记保证恰好一次。 */
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

    // 抢购结果 hash（field = userId）：预扣写 PENDING，落单写 ORDER:{id}，补偿写 FAIL:{原因}。
    public static String resultKey(long activityId) {
        return "seckill:result:" + activityId;
    }

    /** 两个脚本共用的键序：活动 hash、已购集合、结果 hash。 */
    public static List<String> keys(long activityId) {
        return List.of(activityKey(activityId), boughtKey(activityId), resultKey(activityId));
    }

    private static DefaultRedisScript<Long> scriptAt(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }
}
