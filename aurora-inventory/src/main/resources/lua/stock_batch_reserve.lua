-- KEYS: 每个 SKU 的库存 key，最后一个是父订单预占守卫
-- ARGV[1]=guard ttl 秒，ARGV[2]=tradeId，ARGV[3..]=每个 SKU 的数量
-- 返回 1=成功，-1=库存 key 缺失，-2=库存不足，-3=该订单已预占
local n = #KEYS - 1
local guard = KEYS[#KEYS]
if redis.call('EXISTS', guard) == 1 then
    return -3
end
for i = 1, n do
    if redis.call('EXISTS', KEYS[i]) == 0 then
        return -1
    end
end
for i = 1, n do
    if tonumber(redis.call('GET', KEYS[i])) < tonumber(ARGV[i + 2]) then
        return -2
    end
end
for i = 1, n do
    redis.call('DECRBY', KEYS[i], ARGV[i + 2])
end
redis.call('SET', guard, ARGV[2], 'EX', ARGV[1])
return 1
