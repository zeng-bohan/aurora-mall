-- KEYS[1] = 库存 key，KEYS[2] = 预扣守卫 key aurora:stock:reserve-guard:{orderId}
-- ARGV[1] = quantity，ARGV[2] = 守卫 TTL 秒
-- 返回 >=0 本次扣减后的剩余库存
--      -1 库存 key 缺失（调用方按 DB 视图重建后重试）
--      -2 库存不足
--      -3 该订单已预扣过（幂等重放，不重复扣减）
-- 幂等：守卫 SETNX 把「同一订单的重复预扣」挡在扣减之前（客户端重试、上游重投递、
-- 响应丢失后的重放都算）；只有确实没有发生扣减的失败路径（-1/-2）才回滚守卫，
-- 保证后续合法重试仍能进行。
local guard = redis.call('SETNX', KEYS[2], '1')
if guard == 0 then
    return -3
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]))
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then
    redis.call('DEL', KEYS[2])
    return -1
end
local qty = tonumber(ARGV[1])
if stock < qty then
    redis.call('DEL', KEYS[2])
    return -2
end
redis.call('DECRBY', KEYS[1], qty)
return stock - qty
