-- 瞬时失败后把处理权交还：PROCESSING -> PENDING，等待 MQ 重投递。
-- 只有仍处于 PROCESSING 时才重置；已经 ORDER/FAIL 的不覆盖。
-- KEYS[3] = 结果 hash key，ARGV[1] = userId，ARGV[2] = 当前毫秒时间戳
-- 返回 1 = 已重置，0 = 状态已变化（对账或补偿接管）
local state = redis.call('HGET', KEYS[3], ARGV[1])
if not state or string.sub(state, 1, 10) ~= 'PROCESSING' then
    return 0
end
redis.call('HSET', KEYS[3], ARGV[1], 'PENDING:' .. ARGV[2])
return 1
