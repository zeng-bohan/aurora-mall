package com.zengbohan.aurora.ratelimit;

/**
 * 限流器统一接口：尝试获取令牌，立即返回、不阻塞等待。
 */
public interface RateLimiter {

    // 尝试获取 1 个令牌。
    default boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * 尝试获取 {@code permits} 个令牌。
     *
     * @return true = 放行；false = 触发限流
     * @throws IllegalArgumentException permits 非正数
     */
    boolean tryAcquire(int permits);
}
