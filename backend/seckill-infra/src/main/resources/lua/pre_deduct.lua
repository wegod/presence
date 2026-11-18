-- =============================================================================
-- pre_deduct.lua — 秒杀热路径原子预扣
-- =============================================================================
-- 何时用 Lua（本脚本）：多 key 必须同成败——可售/预热校验 → 限购 → 扣库存 → 幂等标记
--   → 写预扣 Hash(HELD) → ZADD 待补偿。任一步失败都不应留下半成品。
-- 何时用 Java：单 key 查询（HGETALL / GET stock）、warmup 写库存、停售 SET、ZRANGEBYSCORE
--   领取待补偿等；不要在 Java 里拆 DECR+INCR+HSET+ZADD。
--
-- 状态机（权威在 KEYS[6] Hash 的 state 字段）：
--   (new) → HELD          本脚本写入
--   HELD  → CONFIRMED     confirm.lua
--   HELD  → ROLLED_BACK   rollback.lua（补偿 / 未落单回补）
--   CONFIRMED → ROLLED_BACK  rollback.lua（取消/超时，订单创建后回补）
--   CONFIRMED / ROLLED_BACK 均为终态（CONFIRMED 仅对 cancel/timeout 可再进 ROLLED_BACK）
--
-- KEYS[1]=stock          seckill:stock:{activityId}:{skuId}
-- KEYS[2]=sale-enabled   seckill:sale-enabled:{activityId}:{skuId}  ("1"/"0")
-- KEYS[3]=user-bought    seckill:user-bought:{activityId}:{skuId}:{userId}
-- KEYS[4]=idempotent     seckill:idempotent:{userId}:{requestId}   （永久保留，rollback 永不 DEL）
-- KEYS[5]=limit          seckill:limit:{activityId}:{skuId}
-- KEYS[6]=pre-deduct     seckill:pre-deduct:{userId}:{requestId}   Hash
-- KEYS[7]=pending ZSet   seckill:pre-deduct:pending
--
-- ARGV[1]=qty
-- ARGV[2]=idemMarker      轻量占位（Java IDEM_MARKER）
-- ARGV[3]=ttlSeconds       >0 时对 bought/idem/record 设 EXPIRE；0=不设 TTL
-- ARGV[4]=dueScoreMs       ZADD score（now + pending-delay）
-- ARGV[5]=member           userId:requestId
-- ARGV[6]=activityId ARGV[7]=skuId ARGV[8]=userId ARGV[9]=requestId ARGV[10]=createdAtMs
--
-- 返回值 {code, detail}：
--   1, 'OK'                 预扣成功，state=HELD 且已入待补偿 ZSet
--   2, <existedMarker>      幂等键已存在（不扣库存、不改 ZSet）
--  -1, 'STOCK_INSUFFICIENT' 库存不足
--  -2, 'USER_LIMIT_EXCEEDED' 超限购
--  -3, 'SALE_DISABLED'      停售
--  -4, 'WARMUP_NOT_READY'   未预热（缺 sale/limit/stock）
-- =============================================================================
local stockKey = KEYS[1]
local saleKey = KEYS[2]
local boughtKey = KEYS[3]
local idemKey = KEYS[4]
local limitKey = KEYS[5]
local recordKey = KEYS[6]
local pendingZset = KEYS[7]

local qty = tonumber(ARGV[1])
local idemMarker = ARGV[2]
local ttl = tonumber(ARGV[3])
local dueScore = tonumber(ARGV[4])
local member = ARGV[5]
local activityId = ARGV[6]
local skuId = ARGV[7]
local userId = ARGV[8]
local requestId = ARGV[9]
local createdAtMs = ARGV[10]

-- 幂等命中：直接返回已有标记（不扣库存、不改 ZSet）
local existed = redis.call('GET', idemKey)
if existed then
  return {2, existed}
end

local sale = redis.call('GET', saleKey)
if (not sale) then
  return {-4, 'WARMUP_NOT_READY'}
end
if sale ~= '1' then
  return {-3, 'SALE_DISABLED'}
end

local userLimit = tonumber(redis.call('GET', limitKey) or '')
if (not userLimit) or userLimit <= 0 then
  return {-4, 'WARMUP_NOT_READY'}
end

local stock = redis.call('GET', stockKey)
if (not stock) then
  return {-4, 'WARMUP_NOT_READY'}
end
stock = tonumber(stock)
if stock < qty then
  return {-1, 'STOCK_INSUFFICIENT'}
end

local bought = tonumber(redis.call('GET', boughtKey) or '0')
if bought + qty > userLimit then
  return {-2, 'USER_LIMIT_EXCEEDED'}
end

redis.call('DECRBY', stockKey, qty)
redis.call('INCRBY', boughtKey, qty)
if ttl > 0 then
  redis.call('EXPIRE', boughtKey, ttl)
  redis.call('SET', idemKey, idemMarker, 'EX', ttl)
else
  redis.call('SET', idemKey, idemMarker)
end

-- 预扣记录：state=HELD（CAS 起点；仅 confirm/rollback 可迁出）
redis.call('HSET', recordKey,
  'state', 'HELD',
  'activityId', activityId,
  'skuId', skuId,
  'userId', userId,
  'requestId', requestId,
  'qty', tostring(qty),
  'createdAtMs', createdAtMs,
  'updatedAtMs', createdAtMs
)
if ttl > 0 then
  redis.call('EXPIRE', recordKey, ttl)
end
-- 待补偿索引：到期后由 RedisCompensate Job 扫描（仅 unsettled HELD 应留在 ZSet）
redis.call('ZADD', pendingZset, dueScore, member)

return {1, 'OK'}
