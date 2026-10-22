-- KEYS: 每个 SKU 的库存 key，最后一个是父订单预占守卫
-- ARGV: 每个 SKU 要归还的数量
-- 返回 1=本次释放，-3=守卫不存在（已释放或从未预占）
local n = #KEYS - 1
local guard = KEYS[#KEYS]
if redis.call('EXISTS', guard) == 0 then
    return -3
end
for i = 1, n do
    redis.call('INCRBY', KEYS[i], ARGV[i])
end
redis.call('DEL', guard)
return 1
