-- KEYS[1] = 释放标记 key aurora:stock:released:{orderId}
-- KEYS[2] = 库存 key
-- ARGV[1] = quantity, ARGV[2] = 标记 TTL 秒
-- 返回 1=本次执行了回滚，0=该订单已回滚过
-- 幂等：关单补偿（MQ close listener 与超时扫描并发、崩溃后重入）可能对同一
-- 订单进入多次，标记位保证 INCRBY 恰好一次，杜绝卖量虚高。
if redis.call('SETNX', KEYS[1], '1') == 1 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
    redis.call('INCRBY', KEYS[2], tonumber(ARGV[1]))
    return 1
end
return 0
