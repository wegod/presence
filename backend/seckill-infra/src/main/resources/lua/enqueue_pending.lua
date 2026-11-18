-- =============================================================================
-- enqueue_pending.lua — 原子「重新挂入待补偿」
-- =============================================================================
-- 何时用 Lua：补齐/刷新预扣 Hash + ZADD 必须同成败（同步 rollback 失败后的失败恢复）。
-- 何时用 Java：单纯 ZRANGEBYSCORE / 单条 ZADD(reschedule) / HGET — 在 Java 直接调。
--
-- ZSet 语义：仅服务「未结清 hold」的补偿 Job。终态不应再入队：
--   HELD（或缺失→补建 HELD）→ 允许 ZADD（return 1）
--   CONFIRMED               → 禁止 ZADD（return 0）——订单侧已确认，不应再补偿扣/回
--   ROLLED_BACK             → 禁止 ZADD（return -1）——已回补完成
--   未知 state              → 禁止 ZADD（return -2）
--
-- KEYS[1]=pre-deduct 记录 Hash
-- KEYS[2]=待补偿 ZSet
--
-- ARGV[1]=member ARGV[2]=dueAtMs ARGV[3]=activityId ARGV[4]=skuId
-- ARGV[5]=userId ARGV[6]=requestId ARGV[7]=qty ARGV[8]=nowMs
--
-- 返回值（Long）：
--   1  enqueued：已 ZADD（HELD 或缺失补建 HELD）
--   0  skipped：state=CONFIRMED，未 ZADD
--  -1  skipped：state=ROLLED_BACK，未 ZADD
--  -2  skipped：未知 state，未 ZADD
-- =============================================================================
local recordKey = KEYS[1]
local pendingZset = KEYS[2]
local member = ARGV[1]
local dueAtMs = tonumber(ARGV[2])
local activityId = ARGV[3]
local skuId = ARGV[4]
local userId = ARGV[5]
local requestId = ARGV[6]
local qty = ARGV[7]
local nowMs = ARGV[8]

if redis.call('EXISTS', recordKey) == 0 then
  -- 记录缺失：按入参补建 HELD 视图，便于 Job 回补
  redis.call('HSET', recordKey,
    'state', 'HELD',
    'activityId', activityId,
    'skuId', skuId,
    'userId', userId,
    'requestId', requestId,
    'qty', tostring(qty),
    'createdAtMs', nowMs,
    'updatedAtMs', nowMs
  )
  redis.call('ZADD', pendingZset, dueAtMs, member)
  return 1
end

local st = redis.call('HGET', recordKey, 'state')

-- 终态：不入队（ZSet 只服务 unsettled holds）
if st == 'CONFIRMED' then
  return 0
end
if st == 'ROLLED_BACK' then
  return -1
end

-- 仅 HELD（或空 state 视为待结清）允许刷新并 ZADD
if st ~= 'HELD' and st ~= false and st ~= nil and st ~= '' then
  return -2
end

redis.call('HSET', recordKey, 'updatedAtMs', nowMs)
redis.call('ZADD', pendingZset, dueAtMs, member)
return 1
