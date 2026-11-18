-- =============================================================================
-- confirm.lua — 预扣确认（订单+Outbox 落库成功 / Job 愈合）
-- =============================================================================
-- 何时用 Lua：Hash state 变更 + ZREM 必须同脚本，避免「已确认仍挂在待补偿」或相反半成功。
-- 何时用 Java：读预扣记录 HGETALL、单条 ZREM（终态清索引）——无需 Lua。
--
-- 状态机 CAS（仅允许合法迁移）：
--   HELD      → CONFIRMED   成功（return 1）
--   CONFIRMED → CONFIRMED    幂等成功，仍确保 ZREM（return 0）
--   ROLLED_BACK → *         非法：禁止确认（return -1）
--   缺失/未知 → *           非法（return -1）
--
-- KEYS[1]=pre-deduct 记录 Hash   seckill:pre-deduct:{userId}:{requestId}
-- KEYS[2]=待补偿 ZSet            seckill:pre-deduct:pending
--
-- ARGV[1]=member       userId:requestId（空串则跳过 ZREM）
-- ARGV[2]=updatedAtMs
--
-- 返回值（Long）：
--   1  ok：HELD → CONFIRMED，已 ZREM
--   0  already confirmed：幂等，已确保 ZREM
--  -1  illegal：ROLLED_BACK / 缺失 / 未知 state，未改库存侧，未强制写 CONFIRMED
-- =============================================================================
local recordKey = KEYS[1]
local pendingZset = KEYS[2]
local member = ARGV[1]
local updatedAtMs = ARGV[2]

local function zrem_if_needed()
  if member ~= '' and member ~= false then
    redis.call('ZREM', pendingZset, member)
  end
end

if redis.call('EXISTS', recordKey) == 0 then
  return -1
end

local st = redis.call('HGET', recordKey, 'state')

-- 幂等：已确认则只清滞留索引
if st == 'CONFIRMED' then
  zrem_if_needed()
  return 0
end

-- 非法：已回补不可再确认
if st == 'ROLLED_BACK' then
  return -1
end

-- CAS：仅 HELD → CONFIRMED
if st ~= 'HELD' then
  return -1
end

redis.call('HSET', recordKey, 'state', 'CONFIRMED', 'updatedAtMs', updatedAtMs)
zrem_if_needed()
return 1
