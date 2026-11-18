#!/usr/bin/env bash
# Up to 10 rounds of bench-seckill; write round-N.md + final.md.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "${SCRIPT_DIR}/_lib.sh"

require_cmd curl python3
require_jq

MAX_ROUNDS="${MAX_ROUNDS:-10}"
CONCURRENCY="${CONCURRENCY:-10000}"
# Default fan-out ≤ Sentinel SECKILL_QPS=500 (override PARALLEL= to stress harder).
PARALLEL="${PARALLEL:-500}"
STOCK="${STOCK:-100}"
# Hint baseline (compose default is 500); loop records suggested adjustments only.
HINT_QPS="${SECKILL_QPS_HINT:-${SECKILL_QPS:-500}}"

mkdir -p "${REPORT_DIR}"

log "bench-loop: max_rounds=${MAX_ROUNDS} CONCURRENCY=${CONCURRENCY} PARALLEL=${PARALLEL}"
log "SECKILL_QPS hint start=${HINT_QPS}"

PASSED_ROUND=0
LAST_PASS="false"
declare -a ROUND_NOTES=()

for round in $(seq 1 "${MAX_ROUNDS}"); do
  log ""
  log "======== ROUND ${round}/${MAX_ROUNDS} ========"
  ROUND_MD="${REPORT_DIR}/round-${round}.md"
  BENCH_RC=0
  set +e
  CONCURRENCY="${CONCURRENCY}" PARALLEL="${PARALLEL}" STOCK="${STOCK}" \
    "${SCRIPT_DIR}/bench-seckill.sh"
  BENCH_RC=$?
  set -e

  SUMMARY="${REPORT_DIR}/last-bench.json"
  if [[ ! -f "${SUMMARY}" ]]; then
    err "Missing ${SUMMARY} after round ${round}"
    cat > "${ROUND_MD}" <<EOF
# Round ${round}

- Result: **FAIL** (no summary written)
- Exit code: ${BENCH_RC}
- SECKILL_QPS hint: ${HINT_QPS}
EOF
    ROUND_NOTES+=("round ${round}: no summary (rc=${BENCH_RC})")
    continue
  fi

  SUCCESS="$(jq -r '.success_count' "${SUMMARY}")"
  VALID="$(jq -r '.valid_orders' "${SUMMARY}")"
  OVERSELL="$(jq -r '.oversell' "${SUMMARY}")"
  HTTP5XX="$(jq -r '.http_5xx' "${SUMMARY}")"
  QPS="$(jq -r '.qps' "${SUMMARY}")"
  ELAPSED="$(jq -r '.elapsed_sec' "${SUMMARY}")"
  PASS="$(jq -r '.pass' "${SUMMARY}")"
  BY_CODE="$(jq -c '.by_code' "${SUMMARY}")"
  ACTIVITY_ID="$(jq -r '.activityId' "${SUMMARY}")"

  ADJUST_NOTE="none"
  if [[ "${OVERSELL}" == "true" ]]; then
    # Oversell should never happen; record critical failure (do not raise QPS)
    ADJUST_NOTE="OVERSELL detected — do not raise QPS; investigate Redis Lua / MySQL stock gates. Keep SECKILL_QPS=${HINT_QPS}."
    LAST_PASS="false"
  elif (( HTTP5XX > CONCURRENCY / 20 )); then
    # >5% 5xx → suggest lowering QPS (~0.8x)
    NEW_HINT="$(python3 -c "print(max(100, int(${HINT_QPS} * 0.8)))")"
    ADJUST_NOTE="Too many 5xx (${HTTP5XX}). Suggested SECKILL_QPS: ${HINT_QPS} → ${NEW_HINT} (stable×0.8). Set env SECKILL_QPS=${NEW_HINT} and restart app."
    HINT_QPS="${NEW_HINT}"
    LAST_PASS="false"
  elif [[ "${PASS}" == "true" ]] && (( SUCCESS <= STOCK )); then
    ADJUST_NOTE="Pass. Optional: if 5xx low and latency OK, SECKILL_QPS=${HINT_QPS} is a candidate stable point (document 0.8× peak if retuning)."
    LAST_PASS="true"
    PASSED_ROUND="${round}"
  else
    ADJUST_NOTE="Did not meet pass criteria. Consider SECKILL_QPS=${HINT_QPS} (or lower if errors persist)."
    LAST_PASS="false"
  fi

  cat > "${ROUND_MD}" <<EOF
# Round ${round}

| Metric | Value |
|--------|-------|
| activityId | ${ACTIVITY_ID} |
| concurrency | ${CONCURRENCY} |
| parallel | ${PARALLEL} |
| stock | ${STOCK} |
| elapsed_sec | ${ELAPSED} |
| qps | ${QPS} |
| success_count (code=0) | ${SUCCESS} |
| valid_orders | ${VALID} |
| http_5xx | ${HTTP5XX} |
| oversell | ${OVERSELL} |
| pass | ${PASS} |
| bench exit | ${BENCH_RC} |
| SECKILL_QPS hint | ${HINT_QPS} |

## Error codes

\`\`\`json
${BY_CODE}
\`\`\`

## Adjustment

${ADJUST_NOTE}
EOF

  ROUND_NOTES+=("round ${round}: pass=${PASS} oversell=${OVERSELL} success=${SUCCESS} 5xx=${HTTP5XX} qps=${QPS} hint=${HINT_QPS}")
  log "Wrote ${ROUND_MD}"
  log "${ADJUST_NOTE}"

  if [[ "${LAST_PASS}" == "true" ]]; then
    log "Early stop: round ${round} passed (no oversell, success_count<=${STOCK})"
    break
  fi
done

FINAL_MD="${REPORT_DIR}/final.md"
STATUS_LINE="FAILED"
if [[ "${LAST_PASS}" == "true" ]]; then
  STATUS_LINE="PASSED at round ${PASSED_ROUND}"
fi

{
  echo "# Bench loop final report"
  echo ""
  echo "- Status: **${STATUS_LINE}**"
  echo "- Rounds run: up to ${MAX_ROUNDS} (stopped early on pass)"
  echo "- CONCURRENCY=${CONCURRENCY} PARALLEL=${PARALLEL} STOCK=${STOCK}"
  echo "- Final SECKILL_QPS hint: **${HINT_QPS}**"
  echo "- Apply via compose/env: \`SECKILL_QPS=${HINT_QPS}\` then restart app"
  echo ""
  echo "## Round notes"
  echo ""
  for n in "${ROUND_NOTES[@]}"; do
    echo "- ${n}"
  done
  echo ""
  echo "## Criteria"
  echo ""
  echo "- Pass: no oversell and success_count ≤ stock (${STOCK})"
  echo "- On high 5xx: lower SECKILL_QPS hint by ×0.8"
  echo "- On oversell: never raise QPS; fix correctness first"
} > "${FINAL_MD}"

log "Wrote ${FINAL_MD}"

if [[ "${LAST_PASS}" != "true" ]]; then
  die "bench-loop finished without a passing round (see ${FINAL_MD})"
fi
log "bench-loop OK"
