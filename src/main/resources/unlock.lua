
-- 获取线程标识
local id = redis.call('get', KEYS[1])
-- 判断锁标识
if(id == ARGV[1]) then
    -- 一致，释放锁
    return redis.call('del',KEYS[1])
end
-- 不一致，啥也不干
return 0
