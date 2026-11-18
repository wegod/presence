# PARALLEL sweep redesign 20261118-162417

STOCK=100 · `BENCH_LOADGEN=threads` · `SECKILL_QPS=1000` · Colima 2C6G · stack healthy on `:8080`

## Matrix

| PARALLEL | CONCURRENCY | elapsed_s | QPS | success | biz_errors | http_5xx | http_0 | oversell | pass | notes |
|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|
| 1000 | 1000 | 2.241 | 446.23 | 100 | 900 | 0 | 0 | false | true | all stock sold; rest `1003` |
| 2000 | 2000 | 5.603 | 356.95 | 100 | 1784 | 116 | 0 | false | true | `http_502`×116 under fan-out |
| 3000 | 3000 | 4.679 | 641.16 | 100 | 2898 | 2 | 0 | false | true | `1003`×2352 + `1012`×546 + `502`×2 |
| 5000 | 5000 | 6.053 | 826.04 | 100 | 4897 | 3 | 0 | false | true | `1003`×4158 + `1012`×739 + `502`×3 |

## Verdict

- **No oversell** at any PARALLEL; `success_count=100=STOCK` every round.
- **http_0 = 0** (no client connect failures); 5xx are `http_502` only (gateway/proxy under burst).
- Biz rejects: `1003` STOCK_INSUFFICIENT (expected after sellout), `1012` RATE_LIMITED (Sentinel QPS=1000) at ≥3k fan-out.
- Per-P JSON: `scripts/report/last-bench-P{1000,2000,3000,5000}.json`

## Gaps / notes

- P=2000 showed elevated `502` (116); higher PARALLEL actually cleaner 5xx but more rate-limit (`1012`).
- Bench script folds HTTP 0 into `http_5xx` counter; table above splits via `by_code` (no `http_0` keys observed).
