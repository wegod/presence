# PARALLEL sweep pool-tune 20261118-170950

STOCK=100 · `BENCH_LOADGEN=threads` · `SECKILL_QPS=1000` · Colima 2C6G · Tomcat/Hikari/Lettuce right-sized for 2C + QPS=1000

## Pool params (before → after)

| Setting | Before | After |
|---|---:|---:|
| `server.tomcat.threads.max` | 400 | **120** |
| `server.tomcat.threads.min-spare` | 50 | **25** |
| `server.tomcat.accept-count` | 1000 | **200** |
| `server.tomcat.max-connections` | 10000 | **500** |
| Hikari `maximum-pool-size` | 50 | **25** |
| Hikari `minimum-idle` | 10 | **8** |
| Lettuce `max-active` | 64 | **24** |
| Lettuce `max-idle` / `min-idle` | 16 / 8 | **12 / 4** |
| Kafka producer linger/batch/buffer | unchanged | unchanged |
| Compose `SECKILL_QPS` | 1000 | 1000 (no change) |

Deploy: `mvn -DskipTests package` + `docker compose … up -d --build app`. Runtime logs show Tomcat workers up to `http-nio-8080-exec-120` (cap honored).

## Matrix (this run, ≤1000)

| PARALLEL | CONCURRENCY | elapsed_s | QPS | success | biz_errors | http_5xx | oversell | pass | notes |
|---:|---:|---:|---:|---:|---:|---:|---|---|---|
| 200 | 200 | 0.825 | 242.42 | 100 | 100 | 0 | false | true | `1003`×100 |
| 500 | 500 | 1.904 | 262.61 | 100 | 400 | 0 | false | true | `1003`×400 |
| 1000 | 1000 | 1.761 | **567.86** | 100 | 900 | 0 | false | true | `1003`×900 |

Per-P JSON: `scripts/report/last-bench-pooltune-P{200,500,1000}.json`

## vs prior async-kafka P=1000 (`parallel-sweep-async-kafka-20261118-165234.md`)

| Metric | async-kafka P=1000 | pool-tune P=1000 | Δ |
|---|---:|---:|---|
| elapsed_s | 2.605 | **1.761** | −32% |
| QPS | 383.88 | **567.86** | **+48%** |
| success / stock | 100 / 100 | 100 / 100 | same |
| http_5xx | 0 | 0 | same |
| by_code | `0`×100, `1003`×900 | same | same |

## Timing sample (300 success-path `placeOrder timing` lines, P=200/500/1000)

| stage | p50 ms | mean ms | max ms |
|---|---:|---:|---:|
| sentinel | 0 | 10 | 103 |
| lua | 32 | 54 | 167 |
| mysql | 33 | 68 | 288 |
| kafkaSubmit | 3 | 80 | 452 |
| total | 254 | 309 | 976 |
| unaccounted (total − sum) | 58 | 97 | — |

- `kafkaSubmit<=2ms`: **148/300** (still mostly non-blocking submit).
- `kafkaSubmit>=50ms`: **123/300** — producer buffer / partition burst still spikes under fan-out.
- vs prior async sweep (p50 total≈450, kafkaSubmit p50≈1): **total p50 dropped ~450→254**; residual cost still MySQL + Lua (+ confirm/unaccounted), not Tomcat pool wait.

## Bottleneck read (after pool tune)

1. **Smaller Tomcat/Hikari/Lettuce pools fit 2C6G** — no 5xx, no oversell; P=1000 wall time improved vs oversized pools.
2. **Hot path still dominated by Redis Lua + MySQL order/outbox** (tens of ms p50); Kafka async submit p50 stays low.
3. **Sentinel did not fire `1012` at ≤1000** in this sweep (stock-out `1003` absorbs surplus); pools no longer fight the QPS cap with 400 threads.
4. Next gains (if needed): MySQL write path / Lua latency under same-SKU contention — not more Tomcat threads.
