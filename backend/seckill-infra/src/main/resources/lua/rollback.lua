-- =============================================================================
-- rollback.lua — 秒杀回补（多 key 原子）
-- =============================================================================
-- 何时用 Lua：INCR 库存 + DECR 限购 + Hash→ROLLED_BACK + ZREM 必须同成败。
--   失败恢复「重新入队」见 enqueue_pending.lua；勿在 Java 拆开 INCR/HSET/ZADD。
-- 何时用 Java：单条查询、reschedule ZADD、显式 ZREM（已终态清索引）。
--
-- 状态机 CAS：
--   HELD      → ROLLED_BACK   补偿 / 未落单回补：调库存+限购，ZREM（return 1）
--   CONFIRMED → ROLLED_BACK   取消/超时（订单创建后）：同样回补库存+限购，ZREM（return 1）
--   ROLLED_BACK → *           幂等：不再加减库存，仍 ZREM 清滞留索引（return 0）
--   缺失记录                  仍允许回补库存并写入 ROLLED_BACK（return 1）——兼容孤儿预扣
--   未知 state                非法：不改库存（return -1）
--
-- 幂等键（idempotent）：本脚本永不 DEL。保留以便同 requestId 重放命中，避免双扣。
--
-- KEYS[1]=stock
-- KEYS[2]=user-bought
-- KEYS[3]=pre-deduct 记录 Hash（可空串则跳过 state 写）
-- KEYS[4]=待补偿 ZSet（可空串则跳过 ZREM）
--
-- ARGV[1]=qty
-- ARGV[2]=member(userId:requestId，可空)
-- ARGV[3]=updatedAtMs
--
-- 返回值（Long）：
--   1  ok：已从 HELD/CONFIRMED/缺失 回补并置 ROLLED_BACK（或仅库存回补）
--   0  already rolled back：幂等，未双回补，已确保 ZREM
--  -1  illegal：未知 state，未改库存
-- =============================================================================
local stockKey = KEYS[1]
local boughtKey = KEYS[2]
local recordKey = KEYS[3]
local pendingZset = KEYS[4]
local qty = tonumber(ARGV[1])
local member = ARGV[2]
local updatedAtMs = ARGV[3]

local function zrem_if_needed()
  if pendingZset ~= '' and pendingZset ~= false and member ~= '' and member ~= false then
    redis.call('ZREM', pendingZset, member)
  end
end

local hasRecord = (recordKey ~= '' and recordKey ~= false and redis.call('EXISTS', recordKey) == 1)
if hasRecord then
  local st = redis.call('HGET', recordKey, 'state')
  -- 已回补：短路，防双回补；仍清滞留索引
  if st == 'ROLLED_BACK' then
    zrem_if_needed()
    return 0
  end
  -- CAS：仅 HELD / CONFIRMED 可进入 ROLLED_BACK
  if st ~= 'HELD' and st ~= 'CONFIRMED' and st ~= false and st ~= nil and st ~= '' then
    return -1
  end
end

if redis.call('EXISTS', stockKey) == 1 then
  redis.call('INCRBY', stockKey, qty)
end

local bought = tonumber(redis.call('GET', boughtKey) or '0')
if bought > 0 then
  local nextBought = bought - qty
  if nextBought <= 0 then
    redis.call('DEL', boughtKey)
  else
    redis.call('SET', boughtKey, nextBought)
  end
end

-- 永不删除 idempotent key（保留重放语义）

if recordKey ~= '' and recordKey ~= false then
  redis.call('HSET', recordKey, 'state', 'ROLLED_BACK', 'updatedAtMs', updatedAtMs)
end
zrem_if_needed()

return 1
