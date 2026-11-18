# 07 — 定时任务与补偿（Jobs & Compensation）

MVP **仅包含**下列 Jobs。**不做**停售巡检轮询 Job（停售由消费侧/失败路径事件驱动）。

## 1. Job 一览

| Job | 目的 | 触发节奏（建议） |
|-----|------|------------------|
| Warmup（库存预热） | 开场前写入 Redis，带 `warmup_token` | 每 30s～1min 扫描；开场前 **10 分钟**窗口 |
| Outbox Compensate | 发送未成功的 Outbox 到 Kafka | 每 1～5s（可配置） |
| Redis Compensate | 扫描 Redis 待补偿 ZSet（到期预扣记录） | 每 1～5s |
| Order Timeout Reconcile | 超时未支付取消并回补 | 每 10～30s |

---

## 2. Warmup Job（开场前 10 分钟预热）

### 2.1 选中条件

- 活动已上架（`status=1`）
- `now` ∈ `[start_time - 10min, start_time)`（实现可含等于边界的清晰定义）
- SKU 需要预热或 token 缺失

### 2.2 写入 Redis

| Key | 值来源 |
|-----|--------|
| `seckill:stock:{activityId}:{skuId}` | `available_stock`（预热时通常等于 total） |
| `seckill:limit:{activityId}:{skuId}` | `per_user_limit` |
| `seckill:sale-enabled:{activityId}:{skuId}` | `sale_enabled` |
| `seckill:warmup-token:{activityId}:{skuId}` | 新生成 token（如 UUID 或 `activityId-skuId-startTime` 哈希） |

### 2.3 硬规则：开抢后永不覆盖

- 若 `now >= start_time`：**禁止**用 DB 库存覆盖 Redis `stock`。
- 若已存在 `warmup-token` 且活动已开抢：跳过覆盖。
- 目的：避免把已预扣进度冲掉，并与「进行中不改库存」一致。

### 2.4 失败与未就绪

- 预热未完成时下单 → `WARMUP_NOT_READY`（Lua/应用层判断 token 或 key 不存在）。

---

## 3. Outbox Compensate Job

### 3.1 扫描

```
status IN (NEW, FAILED)
AND next_retry_at <= now
AND retry_count < maxRetry
```

### 3.2 行为

1. 检查 Kafka 熔断：若熔断打开 → **跳过发送**（等待恢复），可推迟 `next_retry_at`。
2. 熔断关闭 → 按 `payload` 发送至 `seckill.stock.deduct`，key=`skuId`。
3. 成功 → `status=SENT`。
4. 失败 → `retry_count++`，计算退避写入 `next_retry_at`；达上限 → `FAILED` 并告警日志。

### 3.3 与下单路径关系

- 下单同步发送成功：直接 SENT。
- 同步发送失败但订单已落库：保留 NEW/FAILED，由本 Job 在熔断关闭后重试。
- **MVP 默认**：已落库订单不因发送失败立即回滚；全局熔断阻止**新**流量。

---

## 4. Redis Compensate Job

工作队列在 **Redis**，不扫 MySQL `redis_compensate_log`（该表仅审计）。

### 4.1 数据结构

| Key | 类型 | 说明 |
|-----|------|------|
| `seckill:pre-deduct:{userId}:{requestId}` | Hash | 预扣记录：`state` / activityId / skuId / qty / createdAtMs … |
| `seckill:pre-deduct:pending` | ZSet | 待补偿索引；score=到期处理时间(ms)；member=`{userId}:{requestId}` |

预扣状态：`HELD`（已预扣）→ `CONFIRMED`（订单成功，ZREM）或 `ROLLED_BACK`（已回补，ZREM）。

### 4.2 扫描

`ZRANGEBYSCORE pending -inf now`（实现用 claim 脚本领取并推迟 score 作租约）。

### 4.3 行为（每条到期 HELD）

| 条件 | 动作 |
|------|------|
| MySQL 已有活跃订单（PROCESSING/PENDING_PAY/PAID） | 标记 `CONFIRMED` + ZREM |
| 订单已取消 | Lua 回补 → `ROLLED_BACK` + ZREM |
| 无订单且仍在补单宽限（`create-grace-ms`）且仍 HELD | **幂等补建** PROCESSING+Outbox；成功则 CONFIRMED |
| 无订单且超出宽限 | Lua 回补 → `ROLLED_BACK` + ZREM |

审计：每次确认/补单/回补追加 `redis_compensate_log`（DONE/FAILED），**不作队列**。

### 4.4 热路径与生产者

- Lua 预扣成功：同脚本写 HELD 记录 + ZADD（score=`now+pending-delay-ms`）
- 订单+Outbox 成功：CONFIRM + ZREM（尽力而为；丢失由 Job 愈合）
- 写库失败 / 进程崩溃：保留 ZSet，不入 MySQL PENDING 队列
- 取消/超时/Consumer 同步回补失败：重新 ZADD + 写审计行

---

## 5. Order Timeout Reconcile Job

### 5.1 扫描

```
status = PENDING_PAY
AND pay_deadline < now
```

### 5.2 行为（单笔事务/编排）

1. 乐观锁将状态改为 `CANCELED_TIMEOUT`（version CAS）。
2. 若已是终态或 version 冲突 → 跳过（幂等）。
3. 若 `stock_compensated=0`：
   - MySQL `available_stock += qty`
   - Redis Lua 回补 stock + 回滚 user-bought
   - 置 `stock_compensated=1`
4. 回补失败 → 重新 ZADD 待补偿索引并写审计日志，订单状态仍可为已取消（避免用户仍可支付），由 Redis Job 兜底。

### 5.3 支付窗

默认 **15 分钟**。测试可通过缩短 `pay_deadline` 或测试时钟配置冒烟。

### 5.4 与支付竞态

- 回调与超时 Job 并发：均依赖订单 `version` / 支付单状态；只允许一方成功将订单变为 `PAID` 或 `CANCELED_TIMEOUT`。
- 已 `PAID` 不得再超时取消；已取消不得再付成功（回调返回 `ORDER_STATUS_INVALID` 或幂等忽略）。

---

## 6. 明确不做：Stop-Sale Polling Job

| 不做 | 替代 |
|------|------|
| 定时扫描「DB 无库存但仍可售」并停售 | Consumer / 失败路径**事件驱动**设置 `sale_enabled=0` + Redis 开关 |

理由：MVP 库存小、链路短；减少定时任务与误停售窗口。见 backlog 若需巡检兜底。

---

## 7. 补偿与状态机协作图（逻辑）

```
下单成功(PROCESSING)+Outbox NEW
        │
        ├─ 同步 Kafka OK ──────────────► Outbox SENT ──► Consumer ──► PENDING_PAY
        │                                      ▲
        └─ 发送失败/进程崩溃 ──► Outbox Job ────┘（熔断关闭后）

PENDING_PAY ──► 支付成功 ──► PAID
           └─► 超时 Job / 用户取消 ──► CANCELED_* + 回补
                                      └─ 回补失败 ──► ZADD pending ──► Redis Job
```

## 8. 运维注意

- Job 须可重入、短事务、批量有上限（避免一次扫全表拖垮 2G 中间件）。
- 日志打印 `orderId` / `userId:requestId` / `outboxId`，便于验收与排障。
- 服务重启后 Job 自动继续，无需人工清队列（Kafka 内消息 + Outbox + 待补偿 ZSet）。
