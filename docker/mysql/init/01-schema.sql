-- 秒杀 MVP 全量表结构（字段均带 COMMENT）
-- Charset: utf8mb4 / Engine: InnoDB / 时间精度: DATETIME(3)

CREATE DATABASE IF NOT EXISTS seckill
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE seckill;

-- ---------------------------------------------------------------------------
-- 秒杀活动表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seckill_activity (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '活动 ID',
  name        VARCHAR(128) NOT NULL                COMMENT '活动名称',
  start_time  DATETIME(3)  NOT NULL                COMMENT '开抢时间',
  end_time    DATETIME(3)  NOT NULL                COMMENT '结束时间',
  status      TINYINT      NOT NULL DEFAULT 0      COMMENT '0草稿 / 1已上架 / 2已下架',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_status_time (status, start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀活动表';

-- ---------------------------------------------------------------------------
-- 秒杀商品库存表（MySQL 为库存真相源）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seckill_sku_stock (
  id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  activity_id      BIGINT       NOT NULL                COMMENT '活动 ID',
  sku_id           BIGINT       NOT NULL                COMMENT '商品 SKU ID',
  sku_name         VARCHAR(128) NOT NULL                COMMENT '商品展示名',
  total_stock      INT          NOT NULL                COMMENT '总库存，上限 1000',
  available_stock  INT          NOT NULL                COMMENT '可用库存',
  per_user_limit   INT          NOT NULL                COMMENT '每用户限购',
  sale_enabled     TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '1可售 / 0停售',
  unit_price_cent  INT          NOT NULL                COMMENT '单价（分）',
  version          INT          NOT NULL DEFAULT 0      COMMENT '乐观锁版本',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_activity_sku (activity_id, sku_id),
  KEY idx_sku_id (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀商品库存表';

-- ---------------------------------------------------------------------------
-- 订单表
-- 状态: PROCESSING / PENDING_PAY / PAID / PAY_FAILED /
--       CANCELED_TIMEOUT / CANCELED_MANUAL / CANCELED_SYSTEM
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seckill_order (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '订单 ID',
  user_id           BIGINT       NOT NULL                COMMENT '用户 ID',
  request_id        VARCHAR(64)  NOT NULL                COMMENT '前端请求幂等 ID',
  activity_id       BIGINT       NOT NULL                COMMENT '活动 ID',
  sku_id            BIGINT       NOT NULL                COMMENT 'SKU ID',
  qty               INT          NOT NULL DEFAULT 1      COMMENT '数量，默认 1',
  amount_cent       INT          NOT NULL                COMMENT '金额（分）',
  status            VARCHAR(32)  NOT NULL                COMMENT '订单状态',
  version           INT          NOT NULL DEFAULT 0      COMMENT '状态乐观锁',
  pay_deadline      DATETIME(3)  NOT NULL                COMMENT '支付截止时间',
  stock_compensated TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否已回补库存',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_request (user_id, request_id),
  KEY idx_activity_sku (activity_id, sku_id),
  KEY idx_status_deadline (status, pay_deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀订单表';

-- ---------------------------------------------------------------------------
-- Outbox 消息表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seckill_outbox (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '消息 ID',
  aggregate_type  VARCHAR(32)  NOT NULL                COMMENT '如 ORDER',
  aggregate_id    BIGINT       NOT NULL                COMMENT '如 orderId',
  event_type      VARCHAR(64)  NOT NULL                COMMENT '如 STOCK_DEDUCT',
  payload         JSON         NOT NULL                COMMENT '消息体',
  status          TINYINT      NOT NULL DEFAULT 0      COMMENT '0 NEW / 1 SENT / 2 FAILED',
  next_retry_at   DATETIME(3)  NOT NULL                COMMENT '下次重试',
  retry_count     INT          NOT NULL DEFAULT 0      COMMENT '重试次数',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_aggregate_event (aggregate_type, aggregate_id, event_type),
  KEY idx_status_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Outbox 消息表';

-- ---------------------------------------------------------------------------
-- 支付记录表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seckill_pay_record (
  id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '支付记录 ID',
  order_id           BIGINT       NOT NULL                COMMENT '订单 ID',
  user_id            BIGINT       NOT NULL                COMMENT '用户 ID',
  pay_trade_no       VARCHAR(64)  NOT NULL                COMMENT '模拟支付单号',
  amount_cent        INT          NOT NULL                COMMENT '支付金额（分）',
  status             VARCHAR(32)  NOT NULL                COMMENT 'INIT / SUCCESS / FAILED',
  paid_at            DATETIME(3)  NULL                    COMMENT '支付成功时间',
  callback_received  TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否已收到回调',
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_id (order_id),
  UNIQUE KEY uk_pay_trade_no (pay_trade_no),
  KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付记录表';

-- ---------------------------------------------------------------------------
-- Redis 待补偿日志表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS redis_compensate_log (
  id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志 ID',
  compensate_token VARCHAR(64)  NOT NULL                COMMENT '补偿幂等令牌',
  user_id          BIGINT       NOT NULL                COMMENT '用户 ID',
  request_id       VARCHAR(64)  NOT NULL                COMMENT '请求 ID',
  activity_id      BIGINT       NOT NULL                COMMENT '活动 ID',
  sku_id           BIGINT       NOT NULL                COMMENT 'SKU ID',
  qty              INT          NOT NULL                COMMENT '预扣数量',
  action           VARCHAR(32)  NOT NULL                COMMENT 'ROLLBACK_STOCK / CREATE_ORDER 等',
  status           VARCHAR(32)  NOT NULL                COMMENT 'PENDING / DONE / DISCARDED',
  detail           VARCHAR(512) NULL                    COMMENT '备注',
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_compensate_token (compensate_token),
  KEY idx_status (status),
  KEY idx_user_request (user_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Redis 待补偿日志表';

GRANT ALL PRIVILEGES ON seckill.* TO 'seckill'@'%';
FLUSH PRIVILEGES;
