# 06 — API 契约（API Contract）

## 1. 通用约定

### 1.1 统一响应体

```json
{
  "code": "OK",
  "message": "success",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | string | 业务错误码；成功为 `OK`（数值语义 0） |
| `message` | string | 可读说明（可中英） |
| `data` | object \| array \| null | 业务数据 |

HTTP 状态：业务可预期失败仍可返回 **200 + 非 OK code**（推荐）；网关/框架级错误用 4xx/5xx。前端以 `code` 为准。

### 1.2 认证

- `POST /api/auth/login` 成功后 Set-Cookie：`userId=<generated>`（名称以实现为准，文档称 Cookie `userId`）。
- 需登录接口：缺失/非法 Cookie → 业务错误或 401（实现统一即可，验收脚本对齐）。

### 1.3 幂等

- 抢购：`userId`（Cookie）+ body/`requestId`。
- 前端一次点击生成 `requestId`，重试复用。

---

## 2. 错误码（Error Codes）

| code | 含义 |
|------|------|
| `OK` | 成功（=0） |
| `ACTIVITY_NOT_STARTED` | 活动未开始 |
| `ACTIVITY_ENDED` | 活动已结束 |
| `STOCK_INSUFFICIENT` | 库存不足 |
| `USER_LIMIT_EXCEEDED` | 超过用户限购 |
| `IDEMPOTENT_REPLAY` | 幂等重放（可带原订单 data；或与 OK 复用订单，实现二选一须在响应中可区分/可验收） |
| `SECKILL_STOPPED` | 秒杀已停止（熔断/停售总闸） |
| `DEPENDENCY_REDIS_UNAVAILABLE` | Redis 不可用 |
| `DEPENDENCY_MYSQL_UNAVAILABLE` | MySQL 不可用 |
| `DEPENDENCY_KAFKA_UNAVAILABLE` | Kafka 不可用 |
| `ORDER_NOT_FOUND` | 订单不存在 |
| `ORDER_STATUS_INVALID` | 订单状态不允许当前操作 |
| `RATE_LIMITED` | 被限流 |
| `SALE_DISABLED` | SKU 已停售 |
| `WARMUP_NOT_READY` | 预热未完成 |
| `SYSTEM_ERROR` | 未归类系统错误 |

说明：`SECKILL_STOPPED` 可与具体 `DEPENDENCY_*` 同时策略为「优先返回更具体的 DEPENDENCY_*，或统一 SECKILL_STOPPED 并在 message 标明依赖」——实现选定一种并在验收中固定。

---

## 3. 接口列表

### 3.1 登录

`POST /api/auth/login`

- **Auth**：无
- **Body**：可空 `{}`
- **行为**：生成随机 `userId`，写入 Cookie
- **data 示例**：

```json
{
  "userId": 1008611
}
```

---

### 3.2 创建活动（运营）

`POST /api/admin/activities`

- **Auth**：MVP 可简化（同源或无额外鉴权）；至少可被脚本调用
- **Body 示例**：

```json
{
  "name": "双十一手机秒杀",
  "startTime": "2026-11-18T20:00:00.000+08:00",
  "endTime": "2026-11-18T21:00:00.000+08:00",
  "skus": [
    {
      "skuId": 10001,
      "skuName": "Phone X 128G",
      "totalStock": 100,
      "perUserLimit": 1,
      "unitPriceCent": 199900
    }
  ]
}
```

- **规则**：
  - `totalStock` ≤ 1000
  - 创建后库存不可在进行中修改（无改库存接口）
  - 初始 `sale_enabled=1`，`available_stock=totalStock`
- **data 示例**：

```json
{
  "activityId": 1,
  "name": "双十一手机秒杀",
  "startTime": "...",
  "endTime": "...",
  "skus": [
    {
      "skuId": 10001,
      "skuName": "Phone X 128G",
      "totalStock": 100,
      "availableStock": 100,
      "perUserLimit": 1,
      "unitPriceCent": 199900,
      "saleEnabled": true
    }
  ]
}
```

---

### 3.3 查询活动库存（运营）

`GET /api/admin/activities/{id}/stock`

- **data 示例**：

```json
{
  "activityId": 1,
  "items": [
    {
      "skuId": 10001,
      "skuName": "Phone X 128G",
      "totalStock": 100,
      "availableStock": 87,
      "saleEnabled": true
    }
  ]
}
```

---

### 3.4 抢购下单

`POST /api/seckill/orders`

- **Auth**：Cookie `userId` 必填
- **Body**：

```json
{
  "activityId": 1,
  "skuId": 10001,
  "qty": 1,
  "requestId": "fe-uuid-click-001"
}
```

- **行为摘要**：
  1. Sentinel / 熔断检查
  2. Lua 预扣（幂等）
  3. 事务写 `PROCESSING` + Outbox
  4. 发 Kafka；成功则 Outbox SENT
  5. 返回订单（多为 `PROCESSING`）
- **data 示例**：

```json
{
  "orderId": 90001,
  "userId": 1008611,
  "requestId": "fe-uuid-click-001",
  "activityId": 1,
  "skuId": 10001,
  "qty": 1,
  "amountCent": 199900,
  "status": "PROCESSING",
  "payDeadline": null,
  "createdAt": "..."
}
```

- **典型错误**：`ACTIVITY_NOT_STARTED` / `ACTIVITY_ENDED` / `STOCK_INSUFFICIENT` / `USER_LIMIT_EXCEEDED` / `IDEMPOTENT_REPLAY` / `SECKILL_STOPPED` / `DEPENDENCY_*` / `RATE_LIMITED` / `SALE_DISABLED` / `WARMUP_NOT_READY`

---

### 3.5 订单详情

`GET /api/seckill/orders/{id}`

- **Auth**：Cookie；仅本人订单
- **data**：订单完整字段（含 `status`、`payDeadline`、`amountCent` 等）
- **错误**：`ORDER_NOT_FOUND`

---

### 3.6 我的订单列表

`GET /api/seckill/orders`

- **Auth**：Cookie
- **Query（可选）**：`page` / `size`（有默认即可）
- **data 示例**：

```json
{
  "list": [ { "orderId": 90001, "status": "PENDING_PAY", "...": "..." } ],
  "total": 1
}
```

---

### 3.7 取消订单

`POST /api/seckill/orders/{id}/cancel`

- **Auth**：Cookie；本人
- **允许状态**：主要为 `PENDING_PAY`（`PROCESSING` 是否允许取消由实现定，须状态机一致）
- **成功后**：`CANCELED_MANUAL`；回补库存 + 回滚限购（幂等）
- **错误**：`ORDER_NOT_FOUND` / `ORDER_STATUS_INVALID`

---

### 3.8 模拟支付

`POST /api/seckill/orders/{id}/pay`

- **Auth**：Cookie；本人
- **前置**：订单 `PENDING_PAY`
- **行为**：创建/获取 `seckill_pay_record`（`INIT`），触发 mock 成功路径（可同步调回调或异步）
- **data 示例**：

```json
{
  "orderId": 90001,
  "payTradeNo": "MOCK202611180001",
  "status": "INIT",
  "amountCent": 199900
}
```

---

### 3.9 模拟支付回调

`POST /api/seckill/pay/callback`

- **Body 示例**：

```json
{
  "payTradeNo": "MOCK202611180001",
  "orderId": 90001,
  "success": true
}
```

- **行为**：幂等更新支付单与订单 → `PAID` 或 `PAY_FAILED`
- **错误**：`ORDER_NOT_FOUND` / `ORDER_STATUS_INVALID`

---

### 3.10 支付状态查询

`GET /api/seckill/orders/{id}/pay-status`

- **Auth**：Cookie；本人
- **用途**：页面查单；可顺带补偿确认支付结果（mock）
- **data 示例**：

```json
{
  "orderId": 90001,
  "orderStatus": "PAID",
  "payTradeNo": "MOCK202611180001",
  "payStatus": "SUCCESS",
  "paidAt": "...",
  "callbackReceived": true
}
```

---

## 4. 接口一览表

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/auth/login` | 登录，Cookie 写 userId |
| POST | `/api/admin/activities` | 创建活动 + SKU |
| GET | `/api/admin/activities/{id}/stock` | 查库存 |
| POST | `/api/seckill/orders` | 抢购下单（幂等） |
| GET | `/api/seckill/orders/{id}` | 订单详情 |
| GET | `/api/seckill/orders` | 我的订单列表 |
| POST | `/api/seckill/orders/{id}/cancel` | 取消订单 |
| POST | `/api/seckill/orders/{id}/pay` | 模拟支付 |
| POST | `/api/seckill/pay/callback` | 模拟回调 |
| GET | `/api/seckill/orders/{id}/pay-status` | 支付状态 |

## 5. 与前端约定

1. 抢购点击：生成 UUID 作为 `requestId`，存入组件 state；重试不换新。
2. 所有业务失败展示 `message`，并可根据 `code` 分支 UI（未开始倒计时、售罄、停售等）。
3. `PROCESSING` 订单可轮询详情直至 `PENDING_PAY` 或取消类终态（间隔与超时前端自定，避免打爆）。
