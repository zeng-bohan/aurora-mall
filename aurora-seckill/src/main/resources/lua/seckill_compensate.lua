-- 预扣补偿：DB 落单失败时把预扣还回去（S2-1 同步落单路径；S2-2 异步的失败补偿同理）。
-- KEYS[1] = 活动 hash key，KEYS[2] = 已购集合 key
-- ARGV[1] = userId
-- 返回：
--     1 已回补（库存 +1 且移除已购标记）
--     0 无需回补（标记不存在 = 从未预扣，或已经补偿过）
-- 幂等性来自 SREM 的返回值：已购标记是「预扣发生过」的唯一凭据，被移除后重复调用
-- 不会再 +1 库存——补偿重放会直接造成超卖，所以这里必须靠标记本身把关。
-- 仅在活动 hash 仍存在时 +1：避免 hash 已过期却被补偿凭空创建出一个半截 key。
if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
    if redis.call('EXISTS', KEYS[1]) == 1 then
        redis.call('HINCRBY', KEYS[1], 'stock', 1)
    end
    return 1
end
return 0
