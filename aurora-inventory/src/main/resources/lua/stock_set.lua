-- KEYS[1]=库存 key；ARGV[1]=期望当前值（空串表示 key 不应存在），ARGV[2]=新值
-- 返回 1=覆盖成功，0=当前值与期望不符（有在途预占/释放）
local current = redis.call('GET', KEYS[1])
if current == false then
    if ARGV[1] == '' then
        redis.call('SET', KEYS[1], ARGV[2])
        return 1
    end
    return 0
end
if current == ARGV[1] then
    redis.call('SET', KEYS[1], ARGV[2])
    return 1
end
return 0
