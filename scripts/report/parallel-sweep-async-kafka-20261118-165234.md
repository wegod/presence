# PARALLEL sweep async-kafka 20261118-165234

STOCK=100 · `BENCH_LOADGEN=threads` · `SECKILL_QPS=1000` · Colima 2C6G · Kafka `sendAsync` + Outbox callback · AckMode.MANUAL

## Matrix (this run)

| PARALLEL | CONCURRENCY | elapsed_s | QPS | success | biz_errors | http_5xx | http_0 | oversell | pass | notes |
|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|
| 1000 | 1000 | 2.605 | 383.88 | 100 | 900 | 0 | 0 | false | true | `1003`×900 |
| 2000 | 2000 | 3.758 | 532.2 | 100 | 1898 | 2 | 0 | false | true | `1003`×1898; `http_502`×2 |
| 3000 | 3000 | 3.695 | 811.91 | 100 | 2897 | 3 | 0 | false | true | `1003`×2600; `1012`×297; `http_502`×3 |
| 5000 | 5000 | 6.073 | 823.32 | 100 | 4897 | 3 | 0 | false | true | `1003`×4262; `1012`×635; `http_502`×3 |

Per-P JSON: `scripts/report/last-bench-async-P{1000,2000,3000,5000}.json`

## vs previous redesign sweep (`parallel-sweep-redesign-20261118-162417.md`)

| PARALLEL | redesign QPS | async QPS | redesign 5xx | async 5xx | redesign elapsed | async elapsed |
|---:|---:|---:|---:|---:|---:|---:|
| 1000 | 446.23 | 383.88 | 0 | 0 | 2.241 | 2.605 |
| 2000 | 356.95 | **532.2** | **116** | **2** | 5.603 | **3.758** |
| 3000 | 641.16 | **811.91** | 2 | 3 | 4.679 | **3.695** |
| 5000 | 826.04 | 823.32 | 3 | 3 | 6.053 | 6.073 |

### Takeaways

- **No oversell** either sweep; `success=100=STOCK` every round.
- **P=2000**: biggest win — `http_502` 116 → 2, elapsed 5.6s → 3.8s, QPS 357 → 532 (request thread no longer blocked on Kafka `future.get`).
- **P=3000**: QPS up (~641 → 812); 5xx still tiny.
- **P=1000 / 5000**: similar overall; P=1000 slightly slower this run (noise / cold start); P=5000 roughly flat (already dominated by fan-out + Sentinel `1012`).

## Example timing log lines

```
placeOrder timing userId=3000000167 requestId=bench-80-167 orderId=3821 costMs.sentinel=0 costMs.lua=18 costMs.mysql=17 costMs.kafkaSubmit=1 costMs.total=574
placeOrder timing userId=3000000089 requestId=bench-80-89 orderId=3773 costMs.sentinel=0 costMs.lua=87 costMs.mysql=81 costMs.kafkaSubmit=681 costMs.total=1013
Kafka sendAsync submitted orderId=3821 requestId=bench-80-167 skuId=1001 outboxId=3821
Kafka sendAsync success orderId=3821 requestId=bench-80-167 skuId=1001 outboxId=3821
```

## Timing sample (400 success-path `placeOrder timing` lines across sweep)

| stage | p50 ms | mean ms | max ms |
|---|---:|---:|---:|
| sentinel | 0 | 13 | 333 |
| lua | 39 | 64 | 257 |
| mysql | 40 | 57 | 408 |
| kafkaSubmit | **1** | 259 | 1640 |
| total | 450 | 758 | 2427 |
| unaccounted (total − sum) | 211 | 364 | — |

- `kafkaSubmit<=2ms`: **228/400** (majority — true non-blocking submit).
- `kafkaSubmit>=50ms`: **119/400** — under same-SKU partition burst, producer buffer can still block on `KafkaTemplate.send` (not `get`); linger/batch OK but buffer pressure remains.

## Bottleneck read (after async Kafka)

1. **Kafka broker RTT is off the request thread** for most orders (`kafkaSubmit` p50≈1ms). Outbox → SENT happens on producer network-thread callback.
2. **Hot-path time now clusters on MySQL order+outbox + Redis Lua (+ `confirmPreDeduct` in the unaccounted gap)** — tens of ms each at p50, higher under 3k–5k fan-out.
3. **Residual Kafka submit spikes** are producer-buffer / single-partition contention, not sync ack wait; OutboxCompensate still covers FAILED/NEW.
4. **At high PARALLEL**, Sentinel `1012` and stock-out `1003` dominate outcomes; async Kafka mainly cut **502 / thread-hold** at mid fan-out (esp. P=2000).
