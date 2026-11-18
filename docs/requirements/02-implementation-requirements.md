# 02 — 实现要求（Implementation Requirements）

## 1. 总体原则

- 实现 **MVP 核心功能**，优先保证**业务正确**与**可运行/可演示**。
- **预留扩展、避免过度设计**：分桶、软降级、半开恢复、监控大盘等进 backlog，不阻塞 MVP。
- 表名 / 字段名 / 枚举 / 错误码 / Redis Key / 类名均**对人友好**；SQL 与关键代码带中文注释。

## 2. 幂等（Idempotency）

### 2.1 抢购下单

| 项 | 约定 |
|----|------|
| 幂等键 | `userId + requestId` |
| `requestId` 来源 | 前端**一次点击生成**，网络重试**必须复用**同一值 |
| 存储 | MySQL `seckill_order.UNIQUE(user_id, request_id)` + Redis `seckill:idempotent:{userId}:{requestId}` |
| 行为 | 重复请求返回**同一订单**与一致结果（含 `IDEMPOTENT_REPLAY` 或同等语义的成功复用） |

### 2.2 其他必须幂等的路径

| 路径 | 幂等手段 |
|------|----------|
| Kafka 消费扣库存 | Outbox / 订单状态乐观锁；条件更新 `available_stock >= qty` |
| 订单状态变更 | `version` 乐观锁；非法迁移返回 `ORDER_STATUS_INVALID` |
| 支付回调 | `pay_trade_no` / `order_id` 唯一；`callback_received` |
| Redis 库存回补 | 预扣记录 state + 待补偿 ZSet；Lua 回补脚本按 `ROLLED_BACK` 幂等 |
| 取消（手动/超时/系统） | `stock_compensated` 标志，避免重复回补 |

### 2.3 取消时的限购回滚（易漏）

取消类状态（`CANCELED_*`）除回补库存外，必须**回滚 Redis 用户限购计数**（`seckill:user-bought:...`），否则产生「幽灵限购」（Ghost limit）。

## 3. 核心数据可靠性

| 目标 | 要求 |
|------|------|
| 不超卖 | Redis Lua 预扣 + MySQL `available_stock >= qty` 双闸门 |
| 订单与库存一致 | 状态机驱动占用/释放；超时与取消必回补 |
| 消息可靠 | Outbox + Kafka ack/retry + Consumer 手工 ACK |
| 进程可恢复 | Outbox / 待补偿 ZSet / 订单状态可被 Job 扫到并推进 |

### Redis 与 MySQL 职责

| 层 | 角色 |
|----|------|
| Redis | **预约层**（占位、削峰、快速失败），非最终真相 |
| MySQL `seckill_sku_stock` | **真相源**（Authoritative stock） |
| 订单状态 | 占用与回补的业务锚点 |

- 预热带 `warmup_token`；**开抢后禁止覆盖** Redis 库存。
- Redis key miss → **fail-closed**（拒单 / 停售相关错误），不静默当有库存。
- DB 扣减失败 → Lua 回补；同步失败则 ZADD 待补偿 + 审计日志；必要时事件驱动 `sale_enabled=0`。

## 4. 依赖降级（Fail-Closed）

| 依赖 | MVP 策略 | 错误码 |
|------|----------|--------|
| Redis | 熔断 → 停止秒杀 | `SECKILL_STOPPED` / `DEPENDENCY_REDIS_UNAVAILABLE` |
| MySQL | 熔断 → 停止秒杀 | `SECKILL_STOPPED` / `DEPENDENCY_MYSQL_UNAVAILABLE` |
| Kafka | 熔断 → 停止秒杀 | `SECKILL_STOPPED` / `DEPENDENCY_KAFKA_UNAVAILABLE` |

实现：Resilience4j 包裹关键调用与健康探测。熔断打开期间**不再接受新的秒杀预扣**。已在途 Outbox 在依赖恢复、熔断关闭后由补偿任务继续发送。

详见 [adr/001-keep-kafka-circuit-break.md](./adr/001-keep-kafka-circuit-break.md)。

## 5. 前端交互要求

- 活动未开始、已结束、库存不足、限购、停售、依赖不可用等 → 返回**明确业务错误码**，禁止笼统 500。
- 统一响应体：`{ "code", "message", "data" }`（见 `06-api-contract.md`）。
- 抢购按钮：单次点击生成 `requestId`；自动/手动重试复用。

## 6. 自动化测试

| 类型 | 最低覆盖 |
|------|----------|
| 单元测试 | 状态机迁移、Lua 结果码映射、错误码 |
| 集成测试 | 并发扣减不超卖；同用户同 `requestId` 仅一单且结果一致 |
| 验收脚本 | `scripts/accept*.sh`（主流程、幂等、库存不足等） |

## 7. 压测与调参 Loop

| 项 | 要求 |
|----|------|
| 场景 | **10000** 并发抢 **100** 件库存 |
| 断言 | 不超卖；有效订单数 ≤ 100 |
| 工具 | `scripts/bench-seckill.sh`、`bench-loop.sh` |
| 优化轮次 | 最多 **10 轮**（调 Sentinel QPS、Resilience4j、池化参数等） |
| 产出 | 每轮 `scripts/report/round-N.md`；最终配置摘要 |

限流阈值以压测稳定点 × **0.8** 为 Sentinel 初值参考，禁止拍脑袋。

## 8. Code Review（OCR）

- 使用 open-code-review skill，**1–3 轮**自动 loop。
- **只修核心问题**：超卖、幂等、一致性、消息可靠性。
- 风格/命名类问题不阻塞 MVP，除非影响正确性。

## 9. 交付物清单

| 交付物 | 路径/说明 |
|--------|-----------|
| 一键启动 | `scripts/start.sh`（无 Docker 则引导安装） |
| README | 启动、架构、接口示例、限制、扩展 |
| 演示 | `scripts/demo.sh` / `demo.http` |
| 验收 | `scripts/accept.sh` 及专项脚本 |
| 压测 | `bench-seckill.sh` + `bench-loop.sh`（≤10 轮） |
| 知识库 | 本目录 `docs/requirements/**` |

## 10. 活动库存编辑规则（硬约束）

- 库存仅允许在**创建活动**时设定。
- **活动进行中禁止改库存**（无运营改库存 API；若误调应拒绝）。
- 开抢后库存变更路径仅限：扣减、取消/超时回补、系统取消回补。
