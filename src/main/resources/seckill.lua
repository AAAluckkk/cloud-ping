-- 优惠券id
local voucherId=ARGV[1]
-- 用户id
local userId=ARGV[2]
-- 订单id
local orderId=ARGV[3]
-- 优惠券key
local stockKey="seckill:stock:" .. voucherId
-- 订单key
local orderKey="seckill:order:" .. voucherId
--判断库存数量
-- 注意：库存 key 不存在时 redis.call('get') 返回的是 false，
-- tonumber(false) 得到 nil，再拿去和 0 比较就会抛
-- "attempt to compare nil with number"，整个秒杀接口直接报错。
-- 所以先取出来判空，不存在就当成库存为 0 处理（返回 1 = 库存不足）
local stock = tonumber(redis.call('get',stockKey))
if(stock == nil or stock <= 0) then
	return 1
end
--判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember',orderKey,userId)==1)then
	return 2
end
--扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
--下单（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)
--添加队列
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0