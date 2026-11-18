# 05 — 数据模型（Data Model）

命名对人友好；建表须带字段 `COMMENT`（中文）。以下为 **MVP 冻结表结构**。

## 1. 订单状态枚举（Order Status）

存储于 `seckill_order.status`（`VARCHAR(32)`）：

| 值 | 说明 |
|----|------|
| `PROCESSING` | 处理中：Redis 已预扣，等待 MySQL 异步扣库存 |
| `PENDING_PAY` | 待支付：MySQL 库存已扣 |
| `PAID` | 已支付 |
| `PAY_FAILED` | 支付失败 |
| `CANCELED_TIMEOUT` | 超时取消 |
| `CANCELED_MANUAL` | 用户取消 |
| `CANCELED_SYSTEM` | 系统取消 |

合法迁移见 [04-architecture.md](./04-architecture.md) 状态机。更新必须带 `version` 乐观锁。

## 2. 表清单

| 表名 | 说明 |
|------|------|
| `seckill_activity` | 秒杀活动表 |
| `seckill_sku_stock` | 秒杀商品库存表 |
| `seckill_order` | 秒杀订单表 |
| `seckill_outbox` | Outbox 消息表 |
| `seckill_pay_record` | 支付记录表 |
| `redis_compensate_log` | Redis 补偿审计日志表（非工作队列） |

---

## 3. `seckill_activity`（秒杀活动表）

| 字段名 | 类型 | 约束 | COMMENT |
|--------|------|------|---------|
| `id` | BIGINT | PK, AUTO_INCREMENT | 活动 ID |
| `name` | VARCHAR(128) | NOT NULL | 活动名称 |
| `start_time` | DATETIME(3) | NOT NULL | 开抢时间 |
| `end_time` | DATETIME(3) | NOT NULL | 结束时间 |
| `status` | TINYINT | NOT NULL | 活动状态：0草稿 / 1已上架 / 2已下架 |
| `created_at` | DATETIME(3) | NOT NULL | 创建时间 |
| `updated_at` | DATETIME(3) | NOT NULL | 更新时间 |

**索引建议**

- PK(`id`)
- 可选：`INDEX idx_activity_start_end (start_time, end_time)`（预热扫描）

**活动 status 枚举**

| 值 | 含义 |
|----|------|
| 0 | 草稿 DRAFT |
| 1 | 已上架 ONLINE |
| 2 | 已下架 OFFLINE |

---

## 4. `seckill_sku_stock`（秒杀商品库存表）

| 字段名 | 类型 | 约束 | COMMENT |
|--------|------|------|---------|
| `id` | BIGINT | PK, AUTO_INCREMENT | 主键 |
| `activity_id` | BIGINT | NOT NULL | 活动 ID |
| `sku_id` | BIGINT | NOT NULL | 商品 SKU ID |
| `sku_name` | VARCHAR(128) | NOT NULL | 商品展示名 |
| `total_stock` | INT | NOT NULL | 总库存，上限 1000 |
| `available_stock` | INT | NOT NULL | 可用库存（真相源） |
| `per_user_limit` | INT | NOT NULL | 每用户限购数量 |
| `sale_enabled` | TINYINT(1) | NOT NULL | 是否可售：1可售 / 0停售 |
| `unit_price_cent` | INT | NOT NULL | 单价（分） |
| `version` | INT | NOT NULL | 乐观锁版本 |
| `created_at` | DATETIME(3) | NOT NULL | 创建时间 |
| `updated_at` | DATETIME(3) | NOT NULL | 更新时间 |

**约束 / 索引**

- `UNIQUE uk_activity_sku (activity_id, sku_id)`
- 条件扣减示例：`UPDATE ... SET available_stock = available_stock - #{qty}, version = version + 1 WHERE ... AND available_stock >= #{qty} AND version = #{version}`（或等价 CAS）

**业务规则**

- `total_stock` ≤ **1000**；创建时 `available_stock = total_stock`。
- **活动进行中禁止运营改库存**；仅扣减/回补路径变更 `available_stock`。

---

## 5. `seckill_order`（秒杀订单表）

| 字段名 | 类型 | 约束 | COMMENT |
|--------|------|------|---------|
| `id` | BIGINT | PK, AUTO_INCREMENT | 订单 ID |
| `user_id` | BIGINT | NOT NULL | 用户 ID |
| `request_id` | VARCHAR(64) | NOT NULL | 前端请求幂等 ID |
| `activity_id` | BIGINT | NOT NULL | 活动 ID |
| `sku_id` | BIGINT | NOT NULL | 商品 SKU ID |
| `qty` | INT | NOT NULL | 购买数量，默认 1 |
| `amount_cent` | INT | NOT NULL | 订单金额（分） |
| `status` | VARCHAR(32) | NOT NULL | 订单状态 |
| `version` | INT | NOT NULL | 状态乐观锁版本 |
| `pay_deadline` | DATETIME(3) | NOT NULL | 支付截止时间 |
| `stock_compensated` | TINYINT(1) | NOT NULL | 是否已回补库存：0否 / 1是 |
| `created_at` | DATETIME(3) | NOT NULL | 创建时间 |
| `updated_at` | DATETIME(3) | NOT NULL | 更新时间 |

**约束 / 索引**

- `UNIQUE uk_user_request (user_id, request_id)` — 抢购幂等
- `INDEX idx_user_created (user_id, created_at)` — 我的订单列表
- `INDEX idx_status_deadline (status, pay_deadline)` — 超时对账扫描

**默认值建议**

- `qty` 默认 1
- `stock_compensated` 默认 0
- `version` 默认 0
- `pay_deadline` = 下单成功进入待支付路径时设定（或创建时按策略写入；Consumer 转 `PENDING_PAY` 时可校准），支付窗默认 15 分钟

---

## 6. `seckill_outbox`（Outbox 消息表）

| 字段名 | 类型 | 约束 | COMMENT |
|--------|------|------|---------|
| `id` | BIGINT | PK, AUTO_INCREMENT | 消息 ID |
| `aggregate_type` | VARCHAR(32) | NOT NULL | 聚合类型，如 ORDER |
| `aggregate_id` | BIGINT | NOT NULL | 聚合 ID，如 orderId |
| `event_type` | VARCHAR(64) | NOT NULL | 事件类型，如 STOCK_DEDUCT |
| `payload` | JSON | NOT NULL | 消息体 JSON |
| `status` | TINYINT | NOT NULL | 发送状态：0 NEW / 1 SENT / 2 FAILED |
| `next_retry_at` | DATETIME(3) | NOT NULL | 下次重试时间 |
| `retry_count` | INT | NOT NULL | 已重试次数 |
| `created_at` | DATETIME(3) | NOT NULL | 创建时间 |
| `updated_at` | DATETIME(3) | NOT NULL | 更新时间 |

**约束 / 索引**

- `UNIQUE uk_agg_event (aggregate_type, aggregate_id, event_type)`
- `INDEX idx_outbox_poll (status, next_retry_at)` — 补偿扫描

**Outbox status**

| 值 | 含义 |
|----|------|
| 0 | `NEW` 待发送 |
| 1 | `SENT` 已发送成功 |
| 2 | `FAILED` 发送失败（可重试或达上限） |

**典型 payload 字段（逻辑）**

`orderId`, `userId`, `activityId`, `skuId`, `qty`, `requestId` 等，供 Consumer 扣库存使用。

---

## 7. `seckill_pay_record`（支付记录表）

| 字段名 | 类型 | 约束 | COMMENT |
|--------|------|------|---------|
| `id` | BIGINT | PK, AUTO_INCREMENT | 支付记录 ID |
| `order_id` | BIGINT | NOT NULL, UNIQUE | 订单 ID |
| `user_id` | BIGINT | NOT NULL | 用户 ID |
| `pay_trade_no` | VARCHAR(64) | NOT NULL, UNIQUE | 模拟支付单号 |
| `amount_cent` | INT | NOT NULL | 支付金额（分） |
| `status` | VARCHAR(32) | NOT NULL | 支付状态：INIT / SUCCESS / FAILED |
| `paid_at` | DATETIME(3) | NULL | 支付成功时间 |
| `callback_received` | TINYINT(1) | NOT NULL | 是否已收到支付回调：0否 / 1是 |
| `created_at` | DATETIME(3) | NOT NULL | 创建时间 |
| `updated_at` | DATETIME(3) | NOT NULL | 更新时间 |

**支付 status**

| 值 | 含义 |
|----|------|
| `INIT` | 已发起，待结果 |
| `SUCCESS` | 支付成功 |
| `FAILED` | 支付失败 |

回调与查单须幂等：重复回调不重复改订单。

---

## 8. `redis_compensate_log`（Redis 补偿审计日志表）

**角色变更**：工作队列为 Redis ZSet `seckill:pre-deduct:pending`；本表仅追加审计（Job 确认/补单/回补、失败路径备注）。

| 字段名 | 类型 | 约束 | COMMENT |
|--------|------|------|---------|
| `id` | BIGINT | PK, AUTO_INCREMENT | 日志 ID |
| `compensate_token` | VARCHAR(64) | NOT NULL, UNIQUE | 审计行令牌 |
| `user_id` | BIGINT | NOT NULL | 用户 ID |
| `request_id` | VARCHAR(64) | NOT NULL | 前端请求幂等 ID |
| `activity_id` | BIGINT | NOT NULL | 活动 ID |
| `sku_id` | BIGINT | NOT NULL | 商品 SKU ID |
| `qty` | INT | NOT NULL | 预扣/回补数量 |
| `action` | VARCHAR(32) | NOT NULL | 动作：CONFIRM / CREATE_ORDER / ROLLBACK_STOCK 等 |
| `status` | VARCHAR(32) | NOT NULL | 审计结果：DONE / FAILED |
| `detail` | VARCHAR(512) | NULL | 备注说明 |
| `created_at` | DATETIME(3) | NOT NULL | 创建时间 |
| `updated_at` | DATETIME(3) | NOT NULL | 更新时间 |

**索引建议**

- `UNIQUE uk_compensate_token (compensate_token)`
- （可选保留）`INDEX idx_compensate_status (status, updated_at)` — 运维查询，**非 Job 驱动**

**预扣 Redis 状态（权威）**

| state | 含义 |
|-------|------|
| `HELD` | 已预扣，等待落库或补偿 |
| `CONFIRMED` | 订单处理成功 |
| `ROLLED_BACK` | 库存已回补 |

---

## 9. 参考 DDL 片段（示意）

实现以 migration 为准；字段 COMMENT 必须齐全。示例：

```sql
CREATE TABLE seckill_activity (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '活动 ID',
  name        VARCHAR(128) NOT NULL COMMENT '活动名称',
  start_time  DATETIME(3)  NOT NULL COMMENT '开抢时间',
  end_time    DATETIME(3)  NOT NULL COMMENT '结束时间',
  status      TINYINT      NOT NULL COMMENT '活动状态：0草稿 / 1已上架 / 2已下架',
  created_at  DATETIME(3)  NOT NULL COMMENT '创建时间',
  updated_at  DATETIME(3)  NOT NULL COMMENT '更新时间',
  PRIMARY KEY (id)
) COMMENT='秒杀活动表';
```

其余五表按上文字段一一对应生成，禁止缺 COMMENT。

## 10. Redis 与 DB 字段对齐（逻辑）

| Redis Key | 对齐的 DB 概念 |
|-----------|----------------|
| `seckill:stock:{activityId}:{skuId}` | 预扣视图 ↔ `available_stock`（最终以 DB 为准） |
| `seckill:limit:{activityId}:{skuId}` | `per_user_limit` |
| `seckill:sale-enabled:{activityId}:{skuId}` | `sale_enabled` |
| `seckill:warmup-token:{activityId}:{skuId}` | 预热世代；无直接表字段 |
| `seckill:user-bought:...` | 用户占用；取消时必须回滚 |
| `seckill:idempotent:...` | ↔ `uk_user_request` |
| `seckill:pre-deduct:{userId}:{requestId}` | 预扣记录 Hash（HELD/CONFIRMED/ROLLED_BACK） |
| `seckill:pre-deduct:pending` | 待补偿 ZSet（score=到期 ms） |
