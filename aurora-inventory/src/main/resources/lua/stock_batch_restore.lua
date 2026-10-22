-- KEYS: 每个 SKU 的库存 key
-- ARGV: 每个 SKU 要回补的数量
-- 返回 1=成功，-1=有 key 缺失（调用方交给对账任务）
for i = 1, #KEYS do
    if redis.call('EXISTS', KEYS[i]) == 0 then
        return -1
    end
end
for i = 1, #KEYS do
    redis.call('INCRBY', KEYS[i], ARGV[i])
end
return 1
