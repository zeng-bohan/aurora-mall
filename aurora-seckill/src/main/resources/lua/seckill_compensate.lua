-- 预扣补偿：落单失败（同步路径）或投递失败时把预扣还回去，并把结果改成失败原因。
-- KEYS[1] = 活动 hash key，KEYS[2] = 已购集合 key，KEYS[3] = 结果 hash key
-- ARGV[1] = userId，ARGV[2] = 失败原因（ErrorCode 名，供客户端查询）
-- 返回：
--     1 已回补（库存 +1、移除已购标记、结果写 FAIL:{原因}）
--     0 无需回补（标记不存在 = 从未预扣，或已经补偿过）
-- 幂等性来自 SREM 的返回值：已购标记是「预扣发生过」的唯一凭据，被移除后重复调用
-- 不会再 +1 库存——补偿重放会直接造成超卖，所以这里必须靠标记本身把关。
-- 仅在活动 hash 仍存在时 +1：避免 hash 已过期却被补偿凭空创建出一个半截 key。
-- 结果 hash 里的 FAIL 值有独立 TTL（1 天）：失败通知是短时效信息，
-- 不需要跟着活动 key 一起长期保留。
if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
    if redis.call('EXISTS', KEYS[1]) == 1 then
        redis.call('HINCRBY', KEYS[1], 'stock', 1)
    end
    redis.call('HSET', KEYS[3], ARGV[1], 'FAIL:' .. ARGV[2])
    redis.call('EXPIRE', KEYS[3], 86400)
    return 1
end
return 0
