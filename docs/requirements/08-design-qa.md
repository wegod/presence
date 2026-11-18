# 08 — 设计问答（Design Q&A）

对应需求中的「技术方案要思考的问题」。下列为 **已定结论**（与架构计划一致），实现不得偏离冻结契约。

---

## Q1. 库存扣减应放在哪一层？缓存与 DB 如何一致？兜底是什么？

### 结论

| 层级 | 职责 | 权威性 |
|------|------|--------|
| Redis Lua | 预扣：占位、削峰、快速失败 | **非权威**（预约层） |
| MySQL `seckill_sku_stock` | `available_stock` 条件更新 | **权威真相源** |
| 订单状态 | 记录占用/释放 | 对账锚点 |

### 一致性要点

1. **预热**：开场前 10 分钟一次性写入；`warmup_token`；**开抢后禁止覆盖** Redis stock。
2. **双闸门**：Lua 预扣成功 ≠ 最终成交；Consumer 还必须 `available_stock >= qty`。
3. **失败回补**：DB 失败保留 HELD+ZSet 由 Job 收敛；系统取消 → Lua 回补；同步回补失败 → 重新 ZADD + 审计日志。
4. **Redis miss**：fail-closed（`WARMUP_NOT_READY` / `SALE_DISABLED` / 停秒杀），禁止当「无限库存」。
5. **停售**：MySQL 扣失败或无需再卖时，**事件驱动**关 `sale_enabled`；无巡检 Job。

---

## Q2. 如何保证不超卖？如何做幂等（含前端）？

### 不超卖

- Redis 原子预扣（库存与限购同脚本）。
- MySQL 条件扣减 + 乐观锁 / CAS。
- 有效订单占用 ≤ `total_stock`；压测断言。

### 幂等

| 点 | 方案 |
|----|------|
| 下单 | `user_id + request_id` 唯一；Redis idempotent key |
| 前端 | **一次点击一个 requestId**，重试复用（防双单） |
| 消费 | 订单状态机 + Outbox 唯一事件 |
| 支付回调 | `pay_trade_no` / `order_id` 唯一 + `callback_received` |
| 取消回补 | `stock_compensated` + compensate_token |

### 易漏点

取消时必须**回滚 Redis `user-bought`**，否则「幽灵限购」。

---

## Q3. 限流削峰阈值如何定？

### 结论

- **禁止拍脑袋**。用压测：`10000` 并发抢 `100` 库存。
- Sentinel 初值参考：稳定点 QPS × **0.8**。
- 允许 **最多 10 轮** `bench-loop` 调 Sentinel / Resilience4j / 池化参数。
- 削峰层次：Sentinel → Lua 快速失败 → Kafka 异步落库。

「1 万瞬时」= 入口扛住并发与快速失败，不是 1 万笔写库。

---

## Q4. 支付回调丢失怎么办？

### 结论

多层补偿：

1. `seckill_pay_record` 持久化支付意图与结果。
2. 订单页 `GET .../pay-status` 可主动查单/补偿确认（mock）。
3. 超时对账 Job：超时未付 → `CANCELED_TIMEOUT` + 回补。
4. 支付窗默认 **15 分钟**；回调与超时用乐观锁防双花/双取消。

---

## Q5. 依赖不可用如何降级？数据如何退化？

### MVP 已定（Fail-Closed）

| 依赖 | 策略 | 错误码 |
|------|------|--------|
| Redis | 熔断，停止秒杀 | `SECKILL_STOPPED` / `DEPENDENCY_REDIS_UNAVAILABLE` |
| MySQL | 熔断，停止秒杀 | `SECKILL_STOPPED` / `DEPENDENCY_MYSQL_UNAVAILABLE` |
| **Kafka** | **熔断，停止秒杀** | `SECKILL_STOPPED` / `DEPENDENCY_KAFKA_UNAVAILABLE` |

### 说明

- 熔断打开期间：**不再接受新预扣**，避免只占 Redis 无法推进到 MySQL。
- 已在途 Outbox：Kafka 恢复且熔断关闭后由补偿继续发送。
- Outbox **不是**「Kafka 宕机继续接单」的软降级（该方案进 backlog）。
- 数据退化形态：新订单停止增长；已有 `PROCESSING` 可在恢复后继续消费；库存以 MySQL 为准对账。

详见 [adr/001-keep-kafka-circuit-break.md](./adr/001-keep-kafka-circuit-break.md)。

---

## Q6. 2C6G 下目标如何理解？

- Java ≈4G（`-Xmx≈3g`），中间件合计 ≈2G。
- 成功单 ≤ 库存；入口 1 万靠限流 + Lua 挡掉无效请求。
- Kafka/MySQL/Redis 精简配置，避免单组件撑爆内存。

---

## Q7. 能否去掉 Kafka 改同步扣 MySQL？

### 结论：**不采用**

| 方案 | MVP 决策 |
|------|----------|
| 去掉 Kafka，下单同步扣 MySQL | **拒绝** — 丢掉削峰/Outbox 练习位与既定架构 |
| 保留 Kafka + 不可用熔断 | **采用** |
| 库存分桶 | Backlog |
| 纯 DB 无 Redis | **拒绝** — 无法支撑入口洪峰 |

---

## 汇总口诀

> **Redis 预约，MySQL 真相；Lua + 条件更新双闸门；Outbox 可靠投递；Kafka 挂了先停秒杀；取消必回滚限购；压测定限流。**
