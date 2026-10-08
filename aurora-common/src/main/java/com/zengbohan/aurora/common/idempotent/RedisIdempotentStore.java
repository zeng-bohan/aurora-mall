package com.zengbohan.aurora.common.idempotent;

/** 基于 Redis 的请求守卫。Acquire 返回调用方持有的 token（键已被占用时
 *  为 null）；release 在删除前比对 token，因此慢的首次尝试
 *  不会删掉后来投递重新获取的守卫。 */
public interface RedisIdempotentStore {

    // @return 标识本次获取的不透明 token；键已被占用时返回 null。
    String tryAcquire(String key, long ttlSeconds);

    // 仅当该守卫仍由同一个 token 持有者持有时才释放。
    void release(String key, String token);
}
