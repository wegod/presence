#!/usr/bin/env bash
# Fire CONCURRENCY place-order requests against stock=100; assert no oversell.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "${SCRIPT_DIR}/_lib.sh"

require_cmd curl python3
require_jq

CONCURRENCY="${CONCURRENCY:-10000}"
# Default fan-out ≤ Sentinel SECKILL_QPS (compose/app default 500). Override via PARALLEL=.
PARALLEL="${PARALLEL:-500}"
STOCK="${STOCK:-100}"
SKU_ID="${SKU_ID:-1001}"

if ! [[ "${CONCURRENCY}" =~ ^[0-9]+$ ]] || (( CONCURRENCY < 1 )); then
  die "CONCURRENCY must be a positive integer (got: ${CONCURRENCY})"
fi
if (( PARALLEL < 1 )); then
  PARALLEL=1
fi
if (( PARALLEL > CONCURRENCY )); then
  PARALLEL="${CONCURRENCY}"
fi

WORKDIR="$(mktemp -d)"
COOKIE_JAR="$(mktemp)"
SUMMARY_JSON="${REPORT_DIR}/last-bench.json"
mkdir -p "${REPORT_DIR}"
trap 'rm -rf "${WORKDIR}" "${COOKIE_JAR}"' EXIT

log "BASE_URL=${BASE_URL}  UI_URL=${UI_URL}"
log "Bench: CONCURRENCY=${CONCURRENCY} PARALLEL=${PARALLEL} STOCK=${STOCK} (Sentinel QPS default 500)"

step "Health"
health_check

step "Create activity stock=${STOCK} + warmup"
# start +8s gives time to prepare worker fan-out before sale opens
create_activity "bench-$(date +%s)" "${STOCK}" 8 3600 "${SKU_ID}" 9900 1
warmup_activity "${ACTIVITY_ID}"
wait_until_started "${ACTIVITY_START}" 1

# Loadgen modes (avoid xargs/bash fork-bomb at high PARALLEL; host ulimit -u ~2.6k):
#   threads (default) — ThreadPoolExecutor; practical ceiling ~5–6k threads on this host
#   async             — aiohttp (scripts/.venv-bench); scales to 10k concurrent connections
#   xargs             — legacy xargs -P + curl (only safe for modest PARALLEL)
BENCH_LOADGEN="${BENCH_LOADGEN:-threads}"
BENCH_PYTHON="${BENCH_PYTHON:-python3}"
if [[ "${BENCH_LOADGEN}" == "async" && -x "${SCRIPT_DIR}/.venv-bench/bin/python" ]]; then
  BENCH_PYTHON="${SCRIPT_DIR}/.venv-bench/bin/python"
fi

step "Fire ${CONCURRENCY} place-order requests (${BENCH_LOADGEN} P=${PARALLEL})"

t0="$(python3 -c 'import time; print(time.time())')"

if [[ "${BENCH_LOADGEN}" == "xargs" ]]; then
  # xargs appends each stdin item after the fixed args → $1..$4 fixed, $5 = index
  seq 1 "${CONCURRENCY}" | xargs -P "${PARALLEL}" -n 1 bash -c '
    set -euo pipefail
    base_url="$1"
    activity_id="$2"
    sku_id="$3"
    outdir="$4"
    i="$5"
    uid=$((3000000000 + i))
    rid="bench-${activity_id}-${i}"
    out="${outdir}/${i}.json"
    http="${outdir}/${i}.http"
    code="$(curl -sS -o "${out}" -w "%{http_code}" -X POST \
      -H "Content-Type: application/json" \
      -H "Accept: application/json" \
      -H "X-User-Id: ${uid}" \
      -d "{\"activityId\":${activity_id},\"skuId\":${sku_id},\"requestId\":\"${rid}\",\"qty\":1}" \
      "${base_url}/api/seckill/orders" || echo 000)"
    printf "%s" "${code}" > "${http}"
  ' _ "${BASE_URL}" "${ACTIVITY_ID}" "${ACTIVITY_SKU}" "${WORKDIR}"
elif [[ "${BENCH_LOADGEN}" == "async" ]]; then
  "${BENCH_PYTHON}" - "${BASE_URL}" "${ACTIVITY_ID}" "${ACTIVITY_SKU}" "${WORKDIR}" "${CONCURRENCY}" "${PARALLEL}" <<'PY'
import asyncio, json, sys
from pathlib import Path

import aiohttp

base_url, activity_id, sku_id, outdir, concurrency, parallel = sys.argv[1:7]
concurrency, parallel = int(concurrency), int(parallel)
outdir = Path(outdir)
url = f"{base_url}/api/seckill/orders"
sem = asyncio.Semaphore(parallel)

async def one(session: aiohttp.ClientSession, i: int) -> None:
    uid = 3000000000 + i
    rid = f"bench-{activity_id}-{i}"
    body = {
        "activityId": int(activity_id),
        "skuId": int(sku_id),
        "requestId": rid,
        "qty": 1,
    }
    out = outdir / f"{i}.json"
    http = outdir / f"{i}.http"
    async with sem:
        try:
            async with session.post(
                url,
                json=body,
                headers={"X-User-Id": str(uid), "Accept": "application/json"},
            ) as resp:
                raw = await resp.read()
                code = resp.status
        except Exception:
            raw, code = b"", 0
    out.write_bytes(raw)
    http.write_text(str(code))

async def main() -> None:
    timeout = aiohttp.ClientTimeout(total=60, connect=10)
    connector = aiohttp.TCPConnector(limit=parallel, limit_per_host=parallel, ttl_dns_cache=60)
    async with aiohttp.ClientSession(timeout=timeout, connector=connector) as session:
        await asyncio.gather(*(one(session, i) for i in range(1, concurrency + 1)))

asyncio.run(main())
PY
else
  python3 - "${BASE_URL}" "${ACTIVITY_ID}" "${ACTIVITY_SKU}" "${WORKDIR}" "${CONCURRENCY}" "${PARALLEL}" <<'PY'
import json, sys, urllib.error, urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

base_url, activity_id, sku_id, outdir, concurrency, parallel = sys.argv[1:7]
concurrency, parallel = int(concurrency), int(parallel)
outdir = Path(outdir)
url = f"{base_url}/api/seckill/orders"

def one(i: int) -> None:
    uid = 3000000000 + i
    rid = f"bench-{activity_id}-{i}"
    body = json.dumps({
        "activityId": int(activity_id),
        "skuId": int(sku_id),
        "requestId": rid,
        "qty": 1,
    }).encode()
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-User-Id": str(uid),
        },
    )
    out = outdir / f"{i}.json"
    http = outdir / f"{i}.http"
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read()
            code = resp.getcode()
    except urllib.error.HTTPError as e:
        raw = e.read() if e.fp else b""
        code = e.code
    except Exception:
        raw = b""
        code = 0
    out.write_bytes(raw)
    http.write_text(str(code))

with ThreadPoolExecutor(max_workers=parallel) as pool:
    futs = [pool.submit(one, i) for i in range(1, concurrency + 1)]
    for f in as_completed(futs):
        f.result()
PY
fi

t1="$(python3 -c 'import time; print(time.time())')"
ELAPSED="$(python3 -c "print(round(${t1}-${t0}, 3))")"
QPS="$(python3 -c "print(round(${CONCURRENCY}/${ELAPSED}, 2) if ${ELAPSED} > 0 else 0)")"

step "Aggregate results"
# shellcheck disable=SC2016
AGG="$(python3 - "$WORKDIR" "$STOCK" <<'PY'
import json, os, sys
from pathlib import Path
workdir, stock = Path(sys.argv[1]), int(sys.argv[2])
success = http_5xx = http_other = biz_err = 0
by_code = {}
for http_path in workdir.glob("*.http"):
    i = http_path.stem
    try:
        http = int(http_path.read_text().strip() or "0")
    except ValueError:
        http = 0
    body_path = workdir / f"{i}.json"
    code = None
    if body_path.exists():
        try:
            data = json.loads(body_path.read_text() or "{}")
            code = data.get("code")
        except Exception:
            code = None
    key = str(code) if code is not None else f"http_{http}"
    by_code[key] = by_code.get(key, 0) + 1
    if 500 <= http < 600 or http == 0:
        http_5xx += 1
    elif not (200 <= http < 300):
        http_other += 1
    if code == 0:
        success += 1
    elif code is not None:
        biz_err += 1
oversell = success > stock
print(json.dumps({
    "success_count": success,
    "biz_error_count": biz_err,
    "http_5xx": http_5xx,
    "http_other": http_other,
    "by_code": by_code,
    "oversell": oversell,
    "stock": stock,
}))
PY
)"

SUCCESS_COUNT="$(printf '%s' "${AGG}" | jq -r '.success_count')"
BIZ_ERR="$(printf '%s' "${AGG}" | jq -r '.biz_error_count')"
HTTP_5XX="$(printf '%s' "${AGG}" | jq -r '.http_5xx')"
HTTP_OTHER="$(printf '%s' "${AGG}" | jq -r '.http_other')"
OVERSELL="$(printf '%s' "${AGG}" | jq -r '.oversell')"
BY_CODE="$(printf '%s' "${AGG}" | jq -c '.by_code')"

DB_COUNT="$(count_valid_orders_db "${ACTIVITY_ID}" || true)"
if [[ -n "${DB_COUNT}" ]]; then
  VALID_ORDERS="${DB_COUNT}"
  if (( DB_COUNT > STOCK )); then
    OVERSELL="true"
  fi
else
  VALID_ORDERS="${SUCCESS_COUNT}"
  log "DB count unavailable; using success_count=${SUCCESS_COUNT}"
fi

PASS="true"
if [[ "${OVERSELL}" == "true" ]] || (( SUCCESS_COUNT > STOCK )) || (( VALID_ORDERS > STOCK )); then
  PASS="false"
  OVERSELL="true"
fi

OVERSELL_JSON=false
PASS_JSON=true
[[ "${OVERSELL}" == "true" ]] && OVERSELL_JSON=true
[[ "${PASS}" == "true" ]] && PASS_JSON=true || PASS_JSON=false

jq -n \
  --argjson activityId "${ACTIVITY_ID}" \
  --argjson concurrency "${CONCURRENCY}" \
  --argjson parallel "${PARALLEL}" \
  --argjson stock "${STOCK}" \
  --argjson elapsed "${ELAPSED}" \
  --argjson qps "${QPS}" \
  --argjson success "${SUCCESS_COUNT}" \
  --argjson validOrders "${VALID_ORDERS}" \
  --argjson bizErr "${BIZ_ERR}" \
  --argjson http5xx "${HTTP_5XX}" \
  --argjson httpOther "${HTTP_OTHER}" \
  --argjson oversell "${OVERSELL_JSON}" \
  --argjson pass "${PASS_JSON}" \
  --argjson byCode "${BY_CODE}" \
  '{
    activityId:$activityId, concurrency:$concurrency, parallel:$parallel, stock:$stock,
    elapsed_sec:$elapsed, qps:$qps,
    success_count:$success, valid_orders:$validOrders,
    biz_error_count:$bizErr, http_5xx:$http5xx, http_other:$httpOther,
    by_code:$byCode, oversell:$oversell, pass:$pass
  }' > "${SUMMARY_JSON}"

log ""
log "======== BENCH SUMMARY ========"
log "activityId=${ACTIVITY_ID}"
log "requests=${CONCURRENCY} parallel=${PARALLEL} elapsed=${ELAPSED}s QPS=${QPS}"
log "success_count(code=0)=${SUCCESS_COUNT}  valid_orders=${VALID_ORDERS}  stock=${STOCK}"
log "biz_errors=${BIZ_ERR}  http_5xx=${HTTP_5XX}  http_other=${HTTP_OTHER}"
log "by_code=${BY_CODE}"
log "oversell=${OVERSELL}  pass=${PASS}"
log "summary → ${SUMMARY_JSON}"

if [[ "${PASS}" != "true" ]]; then
  die "BENCH FAILED: oversell or success_count>${STOCK}"
fi
log "BENCH PASSED (no oversell, success_count<=${STOCK})"
