-- 1、获取到 优惠券id
-- 2、获取到 用户id
local voucherId = ARGV[3]
local userId = ARGV[2]
local id = ARGV[1]
-- 表名称
-- 1、券表
-- 2、已购买者表
local stockKey = 'seckillvoucher:stock'
local orderKey = 'seckillvoucher:order:' .. voucherId

-- 3、库存数量 < 1  结束。
if(tonumber(redis.call('hget', stockKey, voucherId )) < 1)
then
    return 1
end
-- 4、当前的下单人 包含 当前用户 结束
if(redis.call('SISMEMBER', orderKey, userId) == 1)
then
    -- 重复下单
    return 2
end

-- 5、向缓存中保存下单信息
redis.call('SADD', orderKey, userId)
-- 6、库存数量减少
redis.call("hincrby", stockKey, voucherId, -1)
-- 7、添加信息到消息队列
redis.call("xadd", "stream.orders", "*", "id", id, "userId", userId, "voucherId", voucherId)
return 0
