-- KEYS[1] = 预扣守卫 key aurora:stock:reserve-guard:{orderId}
-- KEYS[2] = 库存 key
-- KEYS[3] = 释放标记 key aurora:stock:released:{orderId}
-- ARGV[1] = quantity，ARGV[2] = 标记 TTL 秒
-- 返回 1 = 本次补回了预扣；0 = 没有可补的（该订单从未预扣过，或已经补过）
-- 用途：order 侧调用预扣但结果未知（超时/断连/空响应）时的补偿。
-- 与关单回滚的安全前提不同：这里必须先确认「该订单确实预扣过」（守卫存在）才允许
-- INCRBY——对从未落地的预扣回补会凭空放大可售库存。释放标记与关单路径共用，
-- 因此两条路径之间也不会重复回补。DB 侧不参与：结果未知时没有订单行，也就没有
-- stock-reserved 事件落账。
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
if redis.call('SETNX', KEYS[3], '1') == 1 then
    redis.call('EXPIRE', KEYS[3], tonumber(ARGV[2]))
    redis.call('INCRBY', KEYS[2], tonumber(ARGV[1]))
    return 1
end
return 0
