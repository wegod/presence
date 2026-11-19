# Presence Flash Sale MVP

单机 Docker（约 2C6G）商品闪购演示。Sentinel 默认 **QPS=500**（`SECKILL_QPS`）。支付窗默认 **1 分钟**（`PAY_WINDOW_MINUTES` / `seckill.pay-window-minutes`），便于超时验收。

**本会话完整演进纪要**（需求 → Plan → 实现/压测/前端/端口）：[`docs/session-export-2026-11-18.md`](docs/session-export-2026-11-18.md)。

## 架构概述

热路径：Sentinel → 活动时间窗 → Redis Lua 预扣 → MySQL 订单+Outbox → confirm 预扣 → Kafka **异步**投递扣库事件 → Consumer 扣 MySQL 库存。失败靠 **OutboxCompensate / RedisCompensate / Timeout / ExpiredCleanup** 等 Job 收敛。FE `:18179` → App `:18178`。图为 C4 风格 Container / Component（flowchart 布局，便于 GitHub 渲染）。

**Outbox（事务发件箱）**：与业务同库的消息表，保证「订单已落库」与「Kafka 扣库事件」最终一致。下单同事务写订单 + Outbox；提交后异步发 Kafka，失败由 `OutboxCompensate` 补发。

### Container（容器）

```mermaid
flowchart TB
  buyer["买家<br/>闪购/支付/查单"]
  ops["运营<br/>活动管理/预热/压测"]
  subgraph FlashSale["闪购系统 Flash Sale"]
    FE["前端容器 Frontend<br/>nginx + React<br/>端口 18179，/api 反代到 App"]
    App["应用容器 App<br/>Spring Boot 3<br/>端口 18178：API、限流、定时任务、熔断"]
    MySQL[("MySQL 容器<br/>InnoDB 真相源<br/>活动/库存/订单/Outbox/支付")]
    Redis[("Redis 容器 AOF<br/>Lua 预扣、HELD 预扣记录<br/>待补偿 ZSet、限购")]
    Kafka[["Kafka 容器<br/>topic seckill.stock.deduct ×3<br/>异步扣库存事件"]]
  end
  buyer -->|"浏览器"| FE
  ops -->|"浏览器"| FE
  FE -->|"HTTP /api"| App
  App -->|"Lua 预扣/确认/回补"| Redis
  App -->|"JDBC 写订单+Outbox"| MySQL
  App -->|"异步投递扣库消息"| Kafka
  Kafka -->|"消费后手工 ACK"| App
```

### Component（App 组件）

```mermaid
flowchart TB
  subgraph web["接入层 web"]
    C1["SeckillOrderController<br/>下单 / 支付 / 取消 / 查单"]
    C2["AdminActivityController<br/>活动 CRUD / 预热 / 库存 / 订单"]
    C3["AuthController<br/>Cookie 登录 / 登出 / 当前用户"]
  end
  subgraph appsvc["应用层 app"]
    S1["SeckillOrderAppService<br/>热路径抢购编排"]
    S2["PayAppService<br/>模拟支付与回调"]
    S3["Activity / Warmup<br/>活动管理与库存预热"]
    S4["SeckillJobs<br/>定时任务入口"]
    S4a["OutboxCompensate<br/>Outbox 补发 Kafka"]
    S4b["RedisCompensate<br/>ZSet 到期确认/补单/回补"]
    S4c["OrderTimeout<br/>待支付超时关单回补"]
    S4d["ExpiredCleanup<br/>过期活动清理 Redis"]
  end
  subgraph infra["基础设施 infra"]
    I1["Sentinel<br/>下单入口 QPS 限流"]
    I2["Resilience4j<br/>Redis/MySQL/Kafka 熔断"]
    I3["SeckillRedisStock<br/>Lua 预扣/确认/回补"]
    I4["MySQL + Outbox<br/>订单落库与消息表"]
    I5["StockDeductProducer<br/>异步发送 + 回调标 SENT"]
    I6["StockDeductConsumer<br/>扣 MySQL 库存 + 手工 ACK"]
  end
  C1 -->|"调用"| S1
  C1 -->|"调用"| S2
  C2 -->|"调用"| S3
  S4 --> S4a
  S4 --> S4b
  S4 --> S4c
  S4 --> S4d
  S1 -->|"限流"| I1
  S1 -->|"预扣"| I3
  S1 -->|"落单"| I4
  S1 -->|"投递"| I5
  S2 -->|"改状态"| I4
  S3 -->|"写缓存"| I3
  S3 -->|"读库"| I4
  S4a -->|"扫表"| I4
  S4a -->|"补发"| I5
  S4b -->|"ZSet/回补"| I3
  S4b -->|"补单"| I4
  S4c -->|"关单"| I4
  S4c -->|"回补"| I3
  S4d -->|"删键"| I3
  I6 -->|"PENDING_PAY/取消"| I4
  I6 -->|"失败回补"| I3
  I3 -.->|"熔断"| I2
  I4 -.->|"熔断"| I2
  I5 -.->|"熔断"| I2
```

### 抢购核心时序

```mermaid
sequenceDiagram
  participant FE as Frontend
  participant API as App
  participant R as RedisLua
  participant DB as MySQL
  participant K as Kafka
  participant Job as OutboxCompensate
  participant C as Consumer

  FE->>API: POST /api/seckill/orders
  API->>API: Sentinel + 活动时间窗
  API->>R: Lua预扣 HELD + ZSet
  alt Lua失败或幂等提示
    API-->>FE: 业务错误或查订单列表
  else Lua成功
    API->>DB: PROCESSING订单 + OutboxNEW
    alt DB失败
      Note over R,DB: HELD 留在 ZSet，RedisCompensate 处理
      API-->>FE: 业务错误
    else DB成功
      API->>R: confirm CONFIRMED + ZREM
      API->>K: sendAsync
      Note over API,K: 回调成功则 Outbox SENT
      API-->>FE: orderId PROCESSING
      alt 异步发送失败或未回调
        Job->>DB: 扫 NEW/FAILED Outbox
        Job->>K: sendSync 补发
        Job->>DB: Outbox SENT
      end
      K->>C: stock.deduct
      alt 活动未过期且扣库成功
        C->>DB: PENDING_PAY
      else 过期或无库存
        C->>DB: CANCELED_SYSTEM
        C->>R: rollback Lua
      end
      C-->>K: 手工ACK
    end
  end
```

### 订单状态机

```mermaid
stateDiagram-v2
  [*] --> PROCESSING: Lua预扣并落库
  PROCESSING --> PENDING_PAY: Consumer扣MySQL成功
  PROCESSING --> CANCELED_SYSTEM: 扣失败或活动过期或停售
  PROCESSING --> CANCELED_MANUAL: 用户取消
  PENDING_PAY --> PAID: 支付成功
  PENDING_PAY --> PAY_FAILED: 支付失败
  PENDING_PAY --> CANCELED_TIMEOUT: 超时约1min
  PENDING_PAY --> CANCELED_MANUAL: 用户取消
  PAID --> [*]
  PAY_FAILED --> [*]
  CANCELED_TIMEOUT --> [*]
  CANCELED_MANUAL --> [*]
  CANCELED_SYSTEM --> [*]
```

乐观锁 `version`；取消类幂等回补库存 + 回滚限购（Lua CAS）。

### 预热

```mermaid
sequenceDiagram
  participant Job as WarmupJob或Admin
  participant DB as MySQL
  participant R as Redis

  Job->>DB: 读活动与SKU库存
  alt 活动已结束
    Job-->>Job: ACTIVITY_ENDED
  else 可预热
    Job->>R: stock limit sale warmup_token
    Note over R: 已有一致 warmup_token 则跳过，开抢后不覆盖 stock
  end
```

### 支付超时

```mermaid
sequenceDiagram
  participant U as User
  participant API as App
  participant Job as OrderTimeoutJob
  participant DB as MySQL
  participant R as Redis

  U->>API: POST .../pay
  API->>DB: PENDING_PAY to PAID
  Note over Job: pay_deadline约为下单加1分钟
  Job->>DB: 扫描过期PENDING_PAY
  Job->>DB: CANCELED_TIMEOUT
  Job->>R: Lua回补库存与限购
```

### Redis 预扣 ZSet 补偿

```mermaid
flowchart TB
  LuaOK[Lua预扣成功 HELD入ZSet] --> WriteDB{MySQL写单}
  WriteDB -->|成功| Confirm[confirm CONFIRMED并ZREM]
  WriteDB -->|失败| Leave[HELD留在ZSet]
  Leave --> Job[RedisCompensateJob扫到期]
  Job -->|已有订单| Confirm2[confirm]
  Job -->|宽限内无单| Create[补建PROCESSING加Outbox]
  Job -->|超宽限| Rollback[Lua回补ROLLED_BACK]
```

工作队列在 Redis ZSet；MySQL `redis_compensate_log` **仅审计**。Kafka 投递补偿见时序中的 **OutboxCompensate**（扫 Outbox NEW/FAILED → `sendSync`）。

### 过期清理

```mermaid
flowchart LR
  Scan[ExpiredCleanupJob] --> Del[幂等删活动域Redis键]
  Del --> Keys["stock / limit / sale / warmup / user-bought"]
```

## 快速启动

```bash
# 需要：Docker（或 Colima）、jq（验收脚本）
./scripts/start.sh
```

未安装 Docker 时脚本会提示安装；若已装 Colima 会自动 `colima start --cpu 2 --memory 6`。

| 组件 | 地址 | 内存约 |
|------|------|--------|
| UI (nginx) | http://localhost:18179 （`/api` → app） | ≤64MB |
| App | http://localhost:18178 | 4G（`-Xmx3g`） |
| MySQL | 3306 / `seckill`/`seckill` | ≤768MB |
| Redis | 6379 AOF | ≤320MB |
| Kafka | 9092 topic `seckill.stock.deduct`×3 | ≤768MB |

本地前端开发（可选，不走 Compose）：

```bash
cd frontend && npm install && npm run dev
# http://localhost:18179 代理 /api → 18178
```

### 前端页面

| 页面 | URL | 说明 |
|------|-----|------|
| 用户闪购 | http://localhost:18179/ | 登录 / 退出、按活动 ID + SKU ID 抢购、订单支付/取消 |
| 活动管理 | http://localhost:18179/admin | 创建活动、分页列表/删除、详情（MySQL+Redis 库存）、预热、运维模拟抢单面板 |

品牌展示为 **Flash Sale**（界面不使用 “Seckill”）。

## 打开前端验收

1. 打开 http://localhost:18179/ ：点击 **登录**，刷新页面后仍保持登录（Cookie `seckill_uid`）；**退出** 后会话与订单列表清空。
2. 在管理页 http://localhost:18179/admin 创建活动（开始时间可设为过去）、**手动预热**，记下 `activityId` / `skuId`。
3. 回到用户页，填写活动 ID + SKU ID，点击 **立即抢购**（每次点击生成新 `requestId`，请求进行中按钮禁用）。
4. 订单列表状态为中文（处理中 / 待支付 / 已支付 / 已取消 / 超时取消等）；待支付可 **支付** 或 **取消**。
5. 管理页：**活动列表**（分页 / 删除）、点选查详情（含 **Redis 库存** 与预热状态）并 **刷新**；已预热时禁用预热按钮；**运维压测面板** 必填活动 ID，按「并发数 × 每人购买次数」**模拟抢单**。
6. **支付超时**：未支付订单约 **1 分钟**后由 Job 超时取消并回补库存。
7. **Sentinel**：默认 QPS=500；超限返回业务码 `RATE_LIMITED`（非 500）。

## 演示 / 验收 / 压测

```bash
./scripts/demo.sh                 # 主流程到 PAID
./scripts/accept.sh               # 健康+主流程+幂等+库存不足+未开始
./scripts/bench-seckill.sh        # 默认 CONCURRENCY=10000 / PARALLEL=500 / STOCK=100
# Sentinel QPS=500：建议 PARALLEL≤500；压更高可显式覆盖
CONCURRENCY=500 PARALLEL=500 STOCK=100 ./scripts/bench-seckill.sh
./scripts/bench-loop.sh           # 最多 10 轮；默认 PARALLEL=500，SECKILL_QPS hint=500
```

HTTP 集合（与下方 curl 对齐）：[`scripts/demo.http`](scripts/demo.http)。知识库：[`docs/requirements/`](docs/requirements/)。

## Demo HTTP APIs（curl）

Cookie 登录；也可用 `-H 'X-User-Id: <id>'` 代替 `-b`。

### 登录 / 当前用户 / 登出

```bash
curl -c /tmp/seckill.jar -X POST http://localhost:18178/api/auth/login \
  -H 'Content-Type: application/json' -d '{}'

curl -b /tmp/seckill.jar http://localhost:18178/api/auth/me

curl -b /tmp/seckill.jar -c /tmp/seckill.jar -X POST http://localhost:18178/api/auth/logout \
  -H 'Content-Type: application/json' -d '{}'
```

### 创建活动

```bash
curl -b /tmp/seckill.jar -X POST http://localhost:18178/api/admin/activities \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"Demo",
    "startTime":"2026-11-18T00:00:00",
    "endTime":"2026-12-31T23:59:59",
    "skuId":1001,
    "skuName":"SKU-1001",
    "totalStock":10,
    "perUserLimit":1,
    "unitPriceCent":9900
  }'
# → data.activityId
```

### 活动列表（分页）

```bash
curl -b /tmp/seckill.jar 'http://localhost:18178/api/admin/activities?page=1&size=20'
# → data: { items, total, page, size }；兼容旧参数 limit（等价 page=1&size=limit）
```

### 删除活动（软删除 / 下架；进行中拒绝）

```bash
curl -b /tmp/seckill.jar -X DELETE http://localhost:18178/api/admin/activities/1
```

### 预热

```bash
curl -b /tmp/seckill.jar -X POST http://localhost:18178/api/admin/activities/1/warmup
```

### 查运营库存（MySQL + Redis）

```bash
curl -b /tmp/seckill.jar http://localhost:18178/api/admin/activities/1/stock
# → availableStock(MySQL) + redisStock / warmed / redisSaleEnabled
```

### 下单

```bash
curl -b /tmp/seckill.jar -X POST http://localhost:18178/api/seckill/orders \
  -H 'Content-Type: application/json' \
  -d '{"activityId":1,"skuId":1001,"requestId":"req-1","qty":1}'
# → data.orderId（多为 PROCESSING，稍后 PENDING_PAY）
```

### 订单详情 / 列表

```bash
curl -b /tmp/seckill.jar http://localhost:18178/api/seckill/orders/1
curl -b /tmp/seckill.jar http://localhost:18178/api/seckill/orders
```

### 支付 / 回调 / 支付状态

```bash
# 模拟支付（创建支付单并走成功回调 → PAID）
curl -b /tmp/seckill.jar -X POST http://localhost:18178/api/seckill/orders/1/pay \
  -H 'Content-Type: application/json' -d '{}'

# 模拟支付回调（可不登录；payTradeNo 来自 pay 响应）
curl -X POST http://localhost:18178/api/seckill/pay/callback \
  -H 'Content-Type: application/json' \
  -d '{"payTradeNo":"MOCK...","success":true}'

curl -b /tmp/seckill.jar http://localhost:18178/api/seckill/orders/1/pay-status
```

### 取消

```bash
curl -b /tmp/seckill.jar -X POST http://localhost:18178/api/seckill/orders/1/cancel
```

### 库存不足（小库存 + 第二用户）

```bash
# 1) 登录用户 A，创建 totalStock=1，warmup，等开抢后下单成功
curl -c /tmp/u1.jar -X POST http://localhost:18178/api/auth/login -H 'Content-Type: application/json' -d '{}'
# create activity with "totalStock":1 … warmup … place order as u1

# 2) 登录用户 B，同一活动再下单 → code 1003 STOCK_INSUFFICIENT（或 1013 SALE_DISABLED）
curl -c /tmp/u2.jar -X POST http://localhost:18178/api/auth/login -H 'Content-Type: application/json' -d '{}'
curl -b /tmp/u2.jar -X POST http://localhost:18178/api/seckill/orders \
  -H 'Content-Type: application/json' \
  -d '{"activityId":1,"skuId":1001,"requestId":"u2-req","qty":1}'
```

完整脚本：`./scripts/accept-stock-insufficient.sh`。

### 重复下单（同 userId + requestId）

```bash
RID="idem-$(uuidgen)"
curl -b /tmp/seckill.jar -X POST http://localhost:18178/api/seckill/orders \
  -H 'Content-Type: application/json' \
  -d "{\"activityId\":1,\"skuId\":1001,\"requestId\":\"$RID\",\"qty\":1}"
# 再发一次相同 body → 同一 orderId（code 0）或 IDEMPOTENT_REPLAY(1005)，仅一笔订单
curl -b /tmp/seckill.jar -X POST http://localhost:18178/api/seckill/orders \
  -H 'Content-Type: application/json' \
  -d "{\"activityId\":1,\"skuId\":1001,\"requestId\":\"$RID\",\"qty\":1}"
```

完整脚本：`./scripts/accept-idempotent.sh`。

### 活动未开始

```bash
# create 时 startTime 设为未来（如 +10min），warmup 后立刻下单 → code 1001 ACTIVITY_NOT_STARTED
curl -b /tmp/seckill.jar -X POST http://localhost:18178/api/seckill/orders \
  -H 'Content-Type: application/json' \
  -d '{"activityId":1,"skuId":1001,"requestId":"early-1","qty":1}'
```

完整脚本：`./scripts/accept-activity-not-started.sh`。

统一响应：`{ "code": 0, "message": "成功", "data": ... }`（失败时 `code` 为业务码）。详见 [`docs/requirements/06-api-contract.md`](docs/requirements/06-api-contract.md)。

## 仓库结构

```
backend/          Spring Boot 3 + JDK21 Maven 多模块
frontend/         React + TS (Vite) — 用户闪购 / 管理端
docker/           Compose + MySQL init + Redis AOF + Kafka + frontend nginx
scripts/          start / demo / accept* / bench* / demo.http
docs/requirements/ 知识库
docs/session-export-2026-11-18.md  本会话导出纪要
```

## 已知限制（MVP）

- **单机**：无多实例互斥领取待补偿 ZSet；库存上限 **≤1000**；**售中不可改库存**。
- **登录**：极简 Cookie（`seckill_uid`）/ 可选 `X-User-Id`；无真实鉴权与管理权限。
- **支付**：模拟支付；支付窗默认 1 分钟。
- **Sentinel**：默认 QPS=500；超限返回业务码（非 HTTP 500）。
- **依赖熔断**：Redis / MySQL / Kafka 不可用均熔断并停止新秒杀流量（业务错误码，非 500）；不做无 Kafka 软接单。
- **停售**：扣库失败事件驱动 `sale_enabled=0`；无停售巡检轮询（有活动过期 Redis 清理 Job）。
- **活动时间**：已结束不可预热/下单；开场前可预热、不可下单。

## 可扩展（Backlog）

Kafka 软降级、停售巡检、依赖半开自动恢复、库存分桶、背压监控、完整对账报表。见 [`docs/requirements/09-iteration-backlog.md`](docs/requirements/09-iteration-backlog.md)。
