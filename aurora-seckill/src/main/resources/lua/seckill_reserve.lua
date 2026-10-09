-- 秒杀预扣：窗口判定 + 一人一单 + 库存扣减，全部在一个脚本里原子完成。
-- KEYS[1] = 活动 hash key seckill:activity:{activityId}
--           （字段 startAt/endAt/perUserLimit/totalStock/stock，毫秒时间戳；由预热写入）
-- KEYS[2] = 已购集合 key seckill:bought:{activityId}（成员是 userId）
-- ARGV[1] = userId，ARGV[2] = 当前毫秒时间戳
-- 返回：
--     1 预扣成功（活动 hash 的 stock -1，并把 userId 加入已购集合）
--    -1 活动未预热或字段缺失（调用方提示运营先触发预热）
--     2 未开始
--     3 已结束
--     4 已售罄
--     5 该用户已参与过（一人一单）
-- 说明：库存与已购标记在同一脚本内变更，不存在「扣了库存没标记」或反之的中间态；
-- 已购集合的 TTL 与活动 hash 同口径（结束后仍保留一天），保证补偿窗口内凭据还在。
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
redis.call('EXPIRE', KEYS[2], math.floor((endAt - now) / 1000) + 86400)
return 1
