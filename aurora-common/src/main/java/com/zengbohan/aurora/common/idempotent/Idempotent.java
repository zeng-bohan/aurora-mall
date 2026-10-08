package com.zengbohan.aurora.common.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 统一幂等：一个注解，按场景映射策略。
 *
 * <ul>
 *   <li>{@link Strategy#REDIS} — 请求去重（下单）：重复请求以
 *       {@code DUPLICATE_REQUEST} 快速失败。</li>
 *   <li>{@link Strategy#DB_DEDUP} — 去重表守卫（MQ 消费者、支付回调）：
 *       重复消息静默跳过。支付回调把它与支付单状态机 + 唯一索引配合使用。</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    Strategy strategy();

    // 记录到去重表的业务类型；默认为声明类名。
    String bizType() default "";

    /**
     * 针对方法参数的 SpEL，例如 {@code "#request.requestId"}。
     * 为空时使用 class#method 加参数哈希。
     */
    String key() default "";

    // {@link Strategy#REDIS} 守卫的 TTL，单位秒。
    long ttlSeconds() default 600;
}
