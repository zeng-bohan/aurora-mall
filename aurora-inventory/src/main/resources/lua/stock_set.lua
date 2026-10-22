-- 管理员设置可售库存：仅当 Redis 当前值与 DB 视图一致时才覆盖。
-- 不一致说明有预扣/释放仍在途，直接覆盖会把在途数量重新放回可售。
-- KEYS[1] = 库存 key
-- ARGV[1] = 期望的当前值（空字符串表示 key 应当缺失）
-- ARGV[2] = 新值
-- 返回 1 = 已设置，0 = 有在途变更，调用方应回滚 DB 事务并让管理员重试
local current = redis.call('GET', KEYS[1])
if not current then
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
