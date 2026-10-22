-- KEYS[1] = 释放标记 key aurora:stock:released:{orderId}
-- KEYS[2] = 库存 key
-- ARGV[1] = quantity, ARGV[2] = 标记 TTL 秒
-- 返回 1=本次执行了回滚，0=该订单已回滚过
-- 幂等：关单补偿（MQ close listener 与超时扫描并发、崩溃后重入）可能对同一
-- 订单进入多次，标记位保证 INCRBY 恰好一次，杜绝卖量虚高。
if redis.call('SETNX', KEYS[1], '1') == 1 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
    -- 库存 key 缺失（Redis 重启/淘汰）时不能创建：此时 DB 才是账本，
    -- 由对账任务按 available - reserved 重建。先 INCRBY 会把该订单的数量
    -- 当成全部可售量，造成严重少卖。
    if redis.call('EXISTS', KEYS[2]) == 1 then
        redis.call('INCRBY', KEYS[2], tonumber(ARGV[1]))
    end
    return 1
end
return 0
