-- 退款回补：每个退款单只把库存加回一次。
-- KEYS[1..n] = 每个 SKU 的库存 key，最后一个是退款守卫 key
-- ARGV[1..n] = 每个 SKU 要回补的数量
-- ARGV[n+1] = refundId，ARGV[n+2] = 守卫 TTL 秒
-- ARGV[n+3..n+2+n] = 每个 SKU 的兜底值（key 缺失时用 DB 当前可售量重建，
--                     该值已包含本次回补，不能再叠加）
-- 返回 1=本次回补，-3=该退款已回补过
local n = #KEYS - 1
local guard = KEYS[#KEYS]
if redis.call('EXISTS', guard) == 1 then
    return -3
end
for i = 1, n do
    if redis.call('EXISTS', KEYS[i]) == 0 then
        redis.call('SET', KEYS[i], ARGV[n + 2 + i])
    else
        redis.call('INCRBY', KEYS[i], ARGV[i])
    end
end
redis.call('SET', guard, ARGV[n + 1], 'EX', ARGV[n + 2])
return 1
