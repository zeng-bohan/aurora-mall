-- KEYS[1] = stock key, ARGV[1] = quantity
-- returns remaining stock on success, -1 when the key is missing, -2 when short
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then
    return -1
end
local qty = tonumber(ARGV[1])
if stock < qty then
    return -2
end
redis.call('DECRBY', KEYS[1], qty)
return stock - qty
