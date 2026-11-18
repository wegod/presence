# OCR Round 1 — Core Issues Only

Focus: oversell / idempotency / consistency / message reliability.

## Findings fixed

1. **Idempotent orphan (consistency)** — Redis Lua 幂等命中但订单未落库时，原先直接返回 `IDEMPOTENT_REPLAY`，库存可能长期占用。现改为短暂等待 → 仍无订单则回补 Redis（含限购/幂等 key）→ 同 `requestId` 重新预扣。
2. **Consumer Redis 回补失败无补偿单（可靠性）** — MySQL 扣库存失败后 Redis 回补异常时写入 `redis_compensate_log`，由定时任务补做。

## Reviewed & accepted

- Dual-gate stock: Lua + MySQL `available_stock >= qty`（压测无超卖）
- `userId+requestId` UNIQUE + Redis 幂等
- Outbox + Kafka manual ACK；发送失败保留 Outbox，熔断阻止新流量
- 取消/超时回补含 Redis 限购

## Skipped / backlog

- PROCESSING 长期卡住的专门超时（依赖 Outbox 补偿 + 熔断策略）
- 全量 10000 并发本机未跑满（已用 CONCURRENCY=500/200 验证不超卖）
