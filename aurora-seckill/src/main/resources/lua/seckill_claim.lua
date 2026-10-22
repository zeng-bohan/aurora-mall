-- 消费者认领受理结果：PENDING -> PROCESSING，防止「对账已归还名额」与
-- 「迟到消息仍在写订单」交叉执行。
-- KEYS[3] = 结果 hash key，ARGV[1] = userId，ARGV[2] = 当前毫秒时间戳
-- 返回：
--     1 可以处理（PENDING 或同一消息的 PROCESSING，或结果已过期）
--     0 不应处理（ORDER/FAIL，或结果缺失）
-- 结果缺失时返回 0：没有预扣就不该凭一条消息直接创建订单。
local state = redis.call('HGET', KEYS[3], ARGV[1])
if not state then
    return 0
end
if string.sub(state, 1, 7) == 'PENDING' or string.sub(state, 1, 10) == 'PROCESSING' then
    redis.call('HSET', KEYS[3], ARGV[1], 'PROCESSING:' .. ARGV[2])
    return 1
end
return 0
