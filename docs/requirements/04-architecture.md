# 04 — 架构设计（Architecture）

## 1. 分层概览

```
┌─────────────────────────────────────────────────────────┐
│  Frontend (React + TS)                                   │
│  Cookie userId · requestId 一次点击生成并复用               │
└───────────────────────────┬─────────────────────────────┘
                            │ HTTP JSON {code,message,data}
┌───────────────────────────▼─────────────────────────────┐
│  seckill-api          Controllers / DTO / Error Codes    │
│  seckill-app          编排、状态机、幂等、熔断门禁           │
│  seckill-domain       订单/库存规则                        │
│  seckill-infra        MyBatis · Redis/Lua · Kafka · Jobs │
└───────┬─────────────┬──────────────┬────────────────────┘
        │             │              │
   ┌────▼────┐   ┌────▼────┐   ┌─────▼─────┐
   │  MySQL  │   │  Redis  │   │   Kafka   │
   │ 真相源  │   │ 预约层  │   │ 异步扣库存 │
   └─────────┘   └─────────┘   └───────────┘
```

横切能力：

- **Sentinel**：入口限流
- **Resilience4j**：Redis / MySQL / Kafka 熔断；打开则**停止秒杀**

## 2. 权威数据与预约层

| 层级 | 职责 | 是否权威 |
|------|------|----------|
| Redis Lua | 预扣（占位 / 削峰 / 快速失败） | 否，预约层 |
| MySQL `seckill_sku_stock` | 条件扣减 `available_stock` | **是，真相源** |
| 订单状态机 | 占用与回补锚点 | 与库存对账 |

原则：

- 一次性预热 + `warmup_token`；**开抢后不覆盖** Redis 库存。
- Redis miss → fail-closed。
- MySQL 扣失败 → 回补 Redis + 补偿日志；可事件驱动停售该 SKU。
- **无**独立停售巡检 Job（MVP）。

## 3. 订单状态机

```
[*] → PROCESSING          Redis 预扣成功并落库
PROCESSING → PENDING_PAY  Kafka 消费 MySQL 扣库存成功
PROCESSING → CANCELED_SYSTEM  MySQL 扣失败或系统停售取消
PENDING_PAY → PAID        支付回调或对账确认
PENDING_PAY → PAY_FAILED  支付明确失败
PENDING_PAY → CANCELED_TIMEOUT  超时未支付
PENDING_PAY → CANCELED_MANUAL   用户取消
终态：PAID / PAY_FAILED / CANCELED_*
```

| 状态 | 含义 |
|------|------|
| `PROCESSING` | 已占 Redis，订单+Outbox 已写，等待/正在异步扣 MySQL |
| `PENDING_PAY` | MySQL 库存已扣，待支付 |
| `PAID` | 支付成功 |
| `PAY_FAILED` | 支付明确失败 |
| `CANCELED_TIMEOUT` | 超时未支付取消 |
| `CANCELED_MANUAL` | 用户取消 |
| `CANCELED_SYSTEM` | 系统取消（扣库存失败、停售等） |

- 状态更新使用 **`version` 乐观锁**。
- 取消类必须**幂等**回补库存并**回滚 Redis 限购计数**（`stock_compensated`）。

## 4. 核心时序：抢购下单

### 4.1 步骤说明

1. **入口**：`POST /api/seckill/orders`；Sentinel 限流；校验活动时间窗。
2. **熔断门禁**：检查 Redis / MySQL / Kafka CircuitBreaker。任一打开 → 返回 `SECKILL_STOPPED` 或对应 `DEPENDENCY_*_UNAVAILABLE`，**不预扣**。
3. **Lua 预扣**：校验 `sale-enabled`、warmup、库存、限购、幂等；成功则扣 `stock`、增 `user-bought`、写 `idempotent`。
4. **MySQL 事务**：插入订单 `PROCESSING` + Outbox `NEW`（同事务）。
5. **DB 失败**：保留 Redis HELD + 待补偿 ZSet（Job 补单或回补）；可写审计日志；返回业务错误。
6. **发 Kafka**（key=`skuId`）：
   - 成功 → Outbox `SENT`；接口返回 `orderId` + `PROCESSING`。
   - 失败并触发熔断 → **后续新请求停止秒杀**；本单 MVP 默认**保留订单+Outbox**，由补偿在熔断关闭后重试发送（接口仍可返回 `PROCESSING`）。
7. **Consumer**：条件扣减 MySQL 库存 → 订单 `PENDING_PAY` → **手工 ACK**。扣失败 → `CANCELED_SYSTEM` + 回补 Redis + 可停售该 SKU。

### 4.2 Sequence（文字版）

```
FE → API: POST /api/seckill/orders (Cookie userId, requestId)
API → Sentinel: 限流
API → CB: Redis/MySQL/Kafka 熔断检查
  alt 熔断打开
    API → FE: SECKILL_STOPPED / DEPENDENCY_*_UNAVAILABLE
  else
    API → Redis: Lua 预扣
      alt 失败 → 业务错误（库存/限购/未开始/停售等）
      else
        API → MySQL: BEGIN; INSERT order PROCESSING + outbox NEW; COMMIT
          alt DB 失败 → Redis 回补 → 业务错误
          else
            API → Kafka: publish seckill.stock.deduct
              alt 发送失败 → 记失败/触发熔断；订单保留走补偿
              else → Outbox SENT
            API → FE: { orderId, status: PROCESSING }
            Kafka → Consumer: deduct
            Consumer → MySQL: available_stock-=qty; status=PENDING_PAY
            Consumer → Kafka: manual ACK
```

### 4.3 Kafka 熔断停秒杀（重点）

| 项 | 约定 |
|----|------|
| 探测 | Resilience4j 包裹 Kafka 发送与健康探测 |
| 打开条件 | 连续发送失败或探活失败达到阈值 |
| 打开后行为 | **拒绝新的秒杀预扣**；返回 `SECKILL_STOPPED` / `DEPENDENCY_KAFKA_UNAVAILABLE` |
| 在途单 | 已写库的 Outbox 在 Kafka 恢复且熔断关闭后由 **Outbox 补偿 Job** 继续发送 |
| 不做（MVP） | 「Kafka 宕机仍持续接单、仅靠 Outbox 缓冲」的软降级 |

同理：Redis / MySQL 不可用亦熔断停秒杀。

> Outbox 在 MVP 中用于**发送成功确认与进程内/Job 重试**，**不是**「Kafka 宕机仍接新单」的软降级手段。

## 5. 库存预热时序

1. Job 扫描：`start_time - 10min <= now < start_time`（或等价窗口）且尚未预热成功的 SKU。
2. 写入 Redis：`stock` / `limit` / `sale-enabled` / `warmup-token`。
3. **若已存在 warmup_token 且活动已开抢**：禁止覆盖 stock（防把已预扣进度冲掉）。
4. 开抢后库存只经 Lua 扣减/回补变更。

## 6. 支付与超时对账

```
PENDING_PAY
  ├─ POST pay → 创建/更新 seckill_pay_record (INIT)
  ├─ mock callback → SUCCESS → 订单 PAID（幂等）
  ├─ 订单页查 pay-status → 可触发补偿确认
  └─ Job: now > pay_deadline 且仍 PENDING_PAY
        → CANCELED_TIMEOUT + 回补 MySQL/Redis + 回滚限购
```

支付窗默认 **15 分钟**。

## 7. 削峰链路

```
客户端洪峰
  → Sentinel 限流（RATE_LIMITED）
  → 熔断门禁（SECKILL_STOPPED / DEPENDENCY_*）
  → Lua 快速失败（STOCK_INSUFFICIENT / USER_LIMIT_EXCEEDED / …）
  → MySQL 短事务（订单+Outbox）
  → Kafka 异步落库（Consumer 扣真相源库存）
```

## 8. 事件驱动停售（无巡检 Job）

触发场景示例：

- Consumer 发现 MySQL `available_stock` 不足或扣减失败
- Redis 关键 key miss 且不应继续售卖

动作：`sale_enabled=0`（DB）+ Redis `seckill:sale-enabled:...=0`；后续 Lua 拒绝预扣（`SALE_DISABLED`）。

## 9. 已知架构限制（MVP）

- 单机；无多活 / 无分库分表。
- 库存 ≤ 1000；无分桶。
- Kafka 故障 = 停新单（硬熔断）。
- 无停售巡检定时任务。
