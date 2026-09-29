-- KEYS[1] = stock key, ARGV[1] = quantity to give back
redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
return tonumber(redis.call('GET', KEYS[1]))
