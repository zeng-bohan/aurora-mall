-- 秒杀库存归还：DB 已记释放后，把活动库存同步回 Redis，按订单只执行一次。
-- KEYS[1] = 活动 hash key，KEYS[2] = 归还守卫 key
-- ARGV[1] = tradeId，ARGV[2] = 归还数量，ARGV[3] = 守卫 TTL 秒
-- 返回 1=本次归还，-3=已归还过，0=活动 key 不存在（无需同步，仍记守卫）
local guard = KEYS[2]
if redis.call('EXISTS', guard) == 1 then
    return -3
end
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('SET', guard, ARGV[1], 'EX', ARGV[3])
    return 0
end
redis.call('HINCRBY', KEYS[1], 'stock', ARGV[2])
redis.call('SET', guard, ARGV[1], 'EX', ARGV[3])
return 1
