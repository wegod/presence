# 03 — 技术栈与中间件（Tech Stack）

## 1. Monorepo 结构

```
presence/
├── backend/                 # Spring Boot 3 + JDK 21 + Maven multi-module
│   ├── seckill-api/         # Controller / DTO / 错误码
│   ├── seckill-app/         # 应用服务、状态机、幂等编排
│   ├── seckill-domain/      # 领域模型、库存/订单规则
│   ├── seckill-infra/       # MyBatis、Redis/Lua、Kafka、Outbox、Jobs
│   └── seckill-bootstrap/   # 启动模块、配置、装配
├── frontend/                # React + TypeScript (Vite)
├── scripts/                 # 启动、验收、演示、异常、压测
├── docker/                  # Compose、MySQL init、Kafka topic、Redis AOF
├── docs/requirements/       # 本知识库
└── README.md
```

## 2. 开发环境

| 组件 | 版本/要求 |
|------|-----------|
| JDK | **21** |
| MySQL | **8** |
| Redis | 开启 **AOF** |
| Kafka | MVP **必须保留**；Topic 3 partitions |
| 容器 | Docker / Docker Compose |
| 构建 | Maven（多模块） |

## 3. 服务端技术选型

| 技术 | 用途 |
|------|------|
| Spring Boot 3.x | Web / 配置 / 生命周期 |
| MyBatis | SQL 映射与持久化 |
| HikariCP | JDBC 连接池 |
| Lettuce | Redis 客户端 |
| Lua | Redis 原子预扣 / 回补 / 幂等读 |
| Sentinel | 单机限流（入口削峰） |
| Resilience4j | 依赖熔断（Redis / MySQL / Kafka） |
| Spring Kafka | Producer / Consumer |

## 4. 前端技术选型

| 技术 | 用途 |
|------|------|
| TypeScript | 类型安全 |
| React | UI |
| Vite | 构建与开发服务器 |

页面能力：登录（Cookie）、抢购（固定 `requestId`）、订单列表/详情、模拟支付、业务错误展示。

## 5. 中间件职责与内存配额

单机目标：**2C6G**。配额已定：

| 组件 | 职责 | MVP 内存份额（约） |
|------|------|-------------------|
| MySQL 8 | 活动/库存/订单/Outbox/支付/补偿日志真相源 | InnoDB buffer pool ≈512MB；容器约 **512–768MB** |
| Redis | 预扣、限购、幂等、预热 token、可售开关；AOF | `maxmemory` ≈ **256MB** |
| Kafka | 异步扣库存事件；3 partitions；producer ack+retry；consumer 手工 ACK | heap ≈256–384MB；容器约 **512–768MB** |
| Java App | 业务 API、Sentinel、Resilience4j、定时任务 | 容器 ≈ **4G**，`-Xmx≈3g`（留 metaspace / 直接内存余量） |

合计：中间件 ≈ **2G**，Java ≈ **4G**。

### Docker 配置提示

- 为 MySQL / Redis / Kafka / App 分别设置 `mem_limit`（或等效），避免某一组件挤占导致 OOM 误伤。
- Kafka 单节点、低副本；关闭不必要的内部 topic 膨胀配置。
- Redis 限制 `maxmemory`，淘汰策略勿误删秒杀关键 key（秒杀窗口内关键 key 应可存活；可用独立 DB/前缀 + 活动结束清理）。

## 6. Kafka 约定

| 项 | 值 |
|----|----|
| Topic | `seckill.stock.deduct` |
| Partitions | **3** |
| Message key | `skuId`（同 SKU 有序） |
| Producer | 异步批量；ack 确认 + 重试；成功后 Outbox → `SENT` |
| Consumer | **关闭自动提交**；业务成功后**手工 ACK** |

Kafka **不可用**时：Resilience4j 熔断 → 停止新秒杀（见架构文档与 ADR）。

## 7. Redis Key 约定（可读命名）

| Key Pattern | 用途 |
|-------------|------|
| `seckill:stock:{activityId}:{skuId}` | 可预扣库存计数 |
| `seckill:limit:{activityId}:{skuId}` | 每用户限购上限 |
| `seckill:user-bought:{activityId}:{skuId}:{userId}` | 用户已购/已占数量 |
| `seckill:idempotent:{userId}:{requestId}` | 下单幂等缓存 |
| `seckill:sale-enabled:{activityId}:{skuId}` | 可售开关（与 DB `sale_enabled` 对齐） |
| `seckill:warmup-token:{activityId}:{skuId}` | 预热令牌；开抢后禁止覆盖库存 |
| `seckill:pre-deduct:{userId}:{requestId}` | 预扣记录 Hash（状态机） |
| `seckill:pre-deduct:pending` | 待补偿 ZSet；Job 扫描到期 member |

## 8. Lua 脚本职责（概要）

| 脚本 | 职责 |
|------|------|
| 预扣 | 校验 sale-enabled / warmup / 库存 / 限购 / 幂等 → 扣 stock、增 user-bought、写 idempotent、写 HELD 记录 + ZADD pending |
| 回补 | 还库存、减 user-bought；记录 → ROLLED_BACK + ZREM；已 ROLLED_BACK 则短路 |
| 领取 pending | ZRANGEBYSCORE 到期 member 并推迟 score 作租约 |

具体 KEYS/ARGV 与返回码在实现模块注释中维护，须与错误码映射一致。

## 9. 本地一键启动依赖

`scripts/start.sh` 应：

1. 检查 Docker；未安装则**引导安装**
2. `docker compose up`（应用内存限额）
3. 执行 SQL init / Kafka topic 创建
4. 健康检查（MySQL / Redis / Kafka / App）
5. 提示继续跑 `accept.sh` / `demo.sh` / `bench-seckill.sh`
