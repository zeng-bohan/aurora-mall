-- 秒杀预扣：窗口判定 + 一人一单 + 库存扣减 + 受理标记，全部在一个脚本里原子完成。
-- KEYS[1] = 活动 hash key seckill:activity:{activityId}
--           （字段 startAt/endAt/perUserLimit/totalStock/stock，毫秒时间戳；由预热写入）
-- KEYS[2] = 已购集合 key seckill:bought:{activityId}（成员是 userId）
-- KEYS[3] = 结果 hash key seckill:result:{activityId}（field = userId）
-- ARGV[1] = userId，ARGV[2] = 当前毫秒时间戳
-- 返回：
--     1 预扣成功（stock -1、userId 入已购集合、结果写 PENDING）
--    -1 活动未预热或字段缺失（调用方提示运营先触发预热）
--     2 未开始
--     3 已结束
--     4 已售罄
--     5 该用户已参与过（一人一单）
-- 说明：库存、已购标记、受理结果在同一脚本内变更，不存在「扣了库存没标记」或
-- 「已扣库存但客户端查不到受理状态」的中间态；三个 key 的 TTL 同口径
-- （活动结束后仍保留一天），保证补偿窗口与结果查询窗口一致。
local activity = redis.call('HMGET', KEYS[1], 'startAt', 'endAt', 'stock')
if not activity[1] or not activity[2] or not activity[3] then
    return -1
end
local now = tonumber(ARGV[2])
local startAt = tonumber(activity[1])
local endAt = tonumber(activity[2])
if now < startAt then
    return 2
end
if now > endAt then
    return 3
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 5
end
if tonumber(activity[3]) <= 0 then
    return 4
end
redis.call('HINCRBY', KEYS[1], 'stock', -1)
redis.call('SADD', KEYS[2], ARGV[1])
local ttl = math.floor((endAt - now) / 1000) + 86400
redis.call('EXPIRE', KEYS[2], ttl)
-- 受理结果：同步模式下随后被 ORDER:{id} 覆盖；MQ 模式下由消费者覆盖为 ORDER:{id} 或 FAIL:{原因}
redis.call('HSET', KEYS[3], ARGV[1], 'PENDING')
redis.call('EXPIRE', KEYS[3], ttl)
return 1
