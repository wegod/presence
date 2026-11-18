# ADR-001：保留 Kafka，不可用时熔断停秒杀

- **状态**：Accepted（MVP 冻结）
- **日期**：2026-11-18
- **标签**：`kafka`, `circuit-breaker`, `seckill`, `outbox`

## 上下文（Context）

秒杀下单主链路为：

1. Redis Lua 预扣  
2. MySQL 同事务写订单（`PROCESSING`）+ Outbox  
3. 发送 Kafka（`seckill.stock.deduct`，3 partitions，key=`skuId`）  
4. Consumer 条件扣 MySQL 库存并转 `PENDING_PAY`

在 2C6G 单机约束下，曾讨论两种替代：

- **A. 去掉 Kafka**：下单事务内同步扣 MySQL，缩短链路、省内存。  
- **B. 保留 Kafka，但宕机时软降级**：继续接单，仅写 Outbox，Kafka 恢复后再发。

同时需回答：Kafka 不可用时，是否还允许新的 Redis 预扣？

## 决策（Decision）

1. **MVP 必须保留 Kafka**（异步扣库存 + Outbox 可靠投递练习位与削峰扩展位）。  
2. **Kafka 不可用 → Resilience4j 熔断 → 停止秒杀**（停止新的预扣/下单）。  
3. 对外错误码：`SECKILL_STOPPED` 与/或 `DEPENDENCY_KAFKA_UNAVAILABLE`（明确业务错误，非裸 500）。  
4. Outbox 用于**发送确认与恢复后重试**，**不作为**「Kafka 宕机仍持续接新单」的手段。  
5. 已落库的在途订单+Outbox：熔断关闭后由 **Outbox 补偿 Job** 继续发送。  
6. Redis / MySQL 不可用采用**同一 fail-closed 策略**（熔断停秒杀）。

## 后果（Consequences）

### 正面

- 架构与需求「Redis → Outbox → Kafka → MySQL」一致，便于演示削峰与手工 ACK。  
- 避免 Kafka 长时间故障时大量订单卡在 `PROCESSING`、Redis 预约与 MySQL 真相长期偏离。  
- 故障语义清晰：依赖挂了就停售，前端可展示明确错误。  
- 与 Redis/MySQL 降级策略对称，门禁实现简单（统一 CircuitBreaker 检查）。

### 负面 / 代价

- Kafka 抖动会导致**拒绝新流量**（可用性换一致性与可运维性）。  
- 需维护 Kafka 内存配额与 Topic 初始化（中间件合计约 2G）。  
- 单笔「已预扣+已写库但发送失败」依赖补偿，增加 Job 与验收路径。

### 不采纳的方案

| 方案 | 原因 |
|------|------|
| 去掉 Kafka，同步扣 MySQL | 违背 MVP「保留 Kafka」冻结项；失去异步削峰扩展位 |
| Kafka 宕机仍接单（纯 Outbox 缓冲） | 延期至 backlog（软降级）；MVP 优先 fail-closed |
| 发送失败立即拒单且不写 Outbox | 丢失已成功预扣的可恢复性；与 Outbox 模式冲突 |

## 实现要点

- Resilience4j 包裹 Kafka 发送与健康探测；连续失败 / 探活失败打开熔断。  
- 抢购 API 在 Lua 预扣**之前**检查熔断状态。  
- Topic：`seckill.stock.deduct`；Producer ack+retry；Consumer 关闭自动提交，成功后手工 ACK。  
- 验收：可选冒烟——停止 Kafka 容器后下单应返回停秒杀类错误码。

## 参考

- [04-architecture.md](../04-architecture.md)  
- [07-jobs-and-compensation.md](../07-jobs-and-compensation.md)  
- [08-design-qa.md](../08-design-qa.md) Q5 / Q7  
- [09-iteration-backlog.md](../09-iteration-backlog.md) B1（Kafka 软降级）
