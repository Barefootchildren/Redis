--参数列表
--优惠券ID
local voucherId=ARGV[1]
--用户ID
local userId=ARGV[2]
--订单ID
local orderId=ARGV[3]

--各种key
--库存key
local stockKey = 'seckill:stock:' .. voucherId
--订单key
local orderKey = 'seckill:order:' .. voucherId

--判断库存是否充足
local stockStr = redis.call('get',stockKey)
if (not stockStr) then
    return 1
end
if(tonumber(stockStr) <= 0) then
    return 1
end

--判断用户是否已经下过单
if(redis.call('sismember',orderKey,userId)==1)then
    return 2
end

--扣库存
redis.call('incrby',stockKey,-1)
--保存已下单的用户信息，防止重复下单
redis.call('sadd',orderKey,userId)
--发送消息到队列中
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0
