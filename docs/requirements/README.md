# 秒杀系统 MVP — 需求与架构知识库

本目录是商品秒杀（Seckill / Flash-Sale）MVP 的**冻结契约与迭代资料库**。实现、验收、压测与后续迭代均以此为准。

## 阅读顺序（建议）

| 顺序 | 文档 | 用途 |
|------|------|------|
| 1 | [01-overview.md](./01-overview.md) | 业务背景、功能范围、MVP 做/不做 |
| 2 | [02-implementation-requirements.md](./02-implementation-requirements.md) | 幂等、可靠性、测试、压测、OCR |
| 3 | [03-tech-stack.md](./03-tech-stack.md) | Monorepo 技术栈、中间件、内存配额 |
| 4 | [04-architecture.md](./04-architecture.md) | 分层、核心时序、Kafka 熔断停秒杀 |
| 5 | [05-data-model.md](./05-data-model.md) | 全量表结构（含 COMMENT）、状态枚举 |
| 6 | [06-api-contract.md](./06-api-contract.md) | REST、错误码、幂等约定 |
| 7 | [07-jobs-and-compensation.md](./07-jobs-and-compensation.md) | 预热 / Outbox / Redis 补偿 / 超时对账 |
| 8 | [08-design-qa.md](./08-design-qa.md) | 设计思考题与已定结论 |
| 9 | [09-iteration-backlog.md](./09-iteration-backlog.md) | 非 MVP 扩展项 |
| — | [adr/001-keep-kafka-circuit-break.md](./adr/001-keep-kafka-circuit-break.md) | ADR：保留 Kafka + 不可用则熔断 |

## 冻结契约速查

| 主题 | 要点 |
|------|------|
| 订单状态 | `PROCESSING` → `PENDING_PAY` → `PAID` / 取消类 / `PAY_FAILED` |
| 核心链路 | Redis Lua 预扣 → MySQL 事务（订单+Outbox）→ Kafka → Consumer 条件扣库存 |
| Kafka | Topic `seckill.stock.deduct`，3 partitions，key=`skuId`；不可用 → 熔断停秒杀 |
| 依赖降级 | Redis / MySQL / Kafka 任一不可用 → `SECKILL_STOPPED` / `DEPENDENCY_*_UNAVAILABLE` |
| 库存 | 创建时设定，上限 1000；**活动进行中禁止改库存** |
| Jobs | 预热、Outbox 补偿、Redis 补偿、订单超时对账；**无停售巡检 Job** |
| 内存（2C6G） | Java ≈4G（`-Xmx≈3g`）；MySQL+Redis+Kafka ≈2G |

## 相关资产

- 启动 / 验收 / 压测：仓库根目录 `scripts/`
- Docker Compose / 初始化：`docker/`
- 仓库总览：根目录 `README.md`

## 变更规则

1. **冻结契约**（状态枚举、表结构、错误码、API、Redis Key、Kafka Topic、降级策略）变更须同步更新本目录对应文档，并新增/修订 ADR。
2. Backlog 项落地前先更新 `09-iteration-backlog.md` 状态，再改实现文档。
3. 压测调参结论可追加到 `scripts/report/` 或本目录补充说明，不覆盖冻结契约。
