# 01 — 业务概览与 MVP 范围

## 1. 业务背景

社交电商 App 每场限时抢购（秒杀 / Flash Sale）可有数百个 SKU。开场瞬间请求量可达日常百倍以上。系统最怕两件事：

1. **库存超卖**（Sold-out integrity failure）
2. **瞬时流量打垮服务**（Thundering herd / overload）

### 容量目标（MVP）

| 维度 | 目标 |
|------|------|
| 部署形态 | 单机 Docker，约 **2C6G** |
| 入口并发 | 约 **1 万瞬时并发请求**打到入口（含被限流/快速失败） |
| 成功单上限 | 有效成功订单数 **≤ 库存**（不超卖） |
| 内存 | Java 容器 ≈ **4G**（`-Xmx≈3g`）；MySQL + Redis + Kafka 合计 ≈ **2G** |

> 「1 万瞬时」= 入口侧并发，不是 1 万笔都写库。大部分请求由 Sentinel / Lua 快速挡掉；有效成交受库存硬约束。

## 2. 要实现的功能

### 2.1 活动与商品管理

- 活动时间窗（`start_time` / `end_time`）
- SKU 总库存、每用户限购、单价
- 总库存上限：**1000**
- MVP：**不支持活动进行中编辑库存**；库存仅创建活动时设定，开抢后只经扣减 / 回补路径变更
- 最小运营接口：创建活动（含 SKU）、查询剩余库存

### 2.2 抢购下单

- 校验活动状态、可售开关、限购
- Redis Lua 预扣库存
- 生成订单（初始 `PROCESSING`）
- 依赖健康检查：Redis / MySQL / Kafka 熔断打开则拒单

### 2.3 订单查询与取消

- 用户查看自己的订单及状态
- 用户手动取消（`CANCELED_MANUAL`）并回补库存 + 回滚 Redis 限购计数

### 2.4 支付（Mock）

- 模拟支付发起与回调
- 支付成功 → `PAID`
- 明确失败 → `PAY_FAILED`
- 支付窗默认 **15 分钟**（`pay_deadline`）

### 2.5 超时未支付

- 自动取消（`CANCELED_TIMEOUT`）
- 回补 MySQL / Redis 库存，并回滚 Redis 用户限购计数

### 2.6 登录（极简）

- 点击登录 → 随机生成 `userId` 写入 Cookie
- 后续接口从 Cookie 识别用户

## 3. 核心抢单流程（需求给定）

```
1. Redis Lua 预扣库存
2. 同一 MySQL 事务写订单表（PROCESSING）+ Outbox
3. 发送 Kafka；成功后更新 Outbox = SENT
4. Consumer 消费 → MySQL 条件扣库存 → 订单转 PENDING_PAY；手工 ACK
```

失败路径：Lua / DB 失败回补 Redis；MySQL 无库存或 Redis miss → 事件驱动停售该 SKU；依赖不可用 → 熔断停秒杀。

## 4. MVP 范围边界

### 4.1 做（In Scope）

| 项 | 说明 |
|----|------|
| Redis Lua 预扣 + MySQL 真相源 | 双闸门防超卖 |
| Outbox + Kafka 异步落库 | Topic 3 partitions，保留 Kafka |
| Resilience4j 熔断 | Redis / MySQL / Kafka → 停秒杀 |
| Sentinel 限流 | 压测定 QPS，最多 10 轮调参 |
| 预热 Job | 开场前 10 分钟，`warmup_token`，开抢后不覆盖 |
| Outbox / Redis 补偿 / 超时对账 Jobs | 见 `07-jobs-and-compensation.md` |
| 模拟支付与回调幂等 | `seckill_pay_record` |
| Cookie 登录 | 无真实鉴权体系 |
| 前端抢购页 | 单次点击固定 `requestId`，重试复用 |
| 验收 / 演示 / 压测脚本 | `scripts/` |

### 4.2 不做（Out of Scope / Backlog）

| 项 | 说明 |
|----|------|
| 活动进行中改库存 | 明确禁止 |
| 停售巡检定时任务 | 消费侧事件驱动停售即可 |
| Kafka 软降级（宕机仍接单） | MVP：不可用则熔断拒单 |
| 依赖恢复自动半开回补流量 | Backlog |
| 库存分桶 / 分片 | Backlog |
| 真实支付 / OAuth / 多机集群 | 非 MVP |
| 完整对账报表 / 背压监控大盘 | Backlog |

详见 [09-iteration-backlog.md](./09-iteration-backlog.md)。

## 5. 成功标准（业务视角）

1. **不超卖**：任意压测 / 并发场景下，已占用库存（有效订单）不超过 `total_stock`。
2. **幂等**：同一 `userId + requestId` 只产生一个有效订单，重复请求结果一致。
3. **可恢复**：进程重启后 Outbox / 补偿表 / 状态机可继续未完成任务。
4. **可演示**：一键启动 + 主流程（创建 → 预热 → 登录 → 下单 → 支付 → PAID）。
5. **依赖故障可感知**：Kafka/Redis/MySQL 不可用时返回明确业务错误码，而非 500。
