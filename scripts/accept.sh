#!/usr/bin/env bash
# Acceptance orchestrator: health + main flow + sibling accept scripts.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "${SCRIPT_DIR}/_lib.sh"

require_cmd curl python3
require_jq

FAILED=0

run_case() {
  local name="$1"
  shift
  log ""
  log "======== ${name} ========"
  # Subshell so die/exit inside a case does not abort the suite
  if ( "$@" ); then
    log "PASS: ${name}"
  else
    err "FAIL: ${name}"
    FAILED=1
  fi
}

main_flow() {
  local jar
  jar="$(mktemp)"
  # shellcheck disable=SC2064
  trap "rm -f '${jar}'" EXIT
  COOKIE_JAR="${jar}"

  step "Health"
  health_check

  step "Login"
  login "${COOKIE_JAR}"

  step "Create activity + warmup + order + pay"
  create_activity "accept-main-$(date +%s)" 10 5 3600 1001 9900 1
  warmup_activity "${ACTIVITY_ID}"
  wait_until_started "${ACTIVITY_START}" 1

  local rid order_id status
  rid="accept-main-$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')"
  place_order "${ACTIVITY_ID}" "${ACTIVITY_SKU}" "${rid}"
  assert_http_2xx
  assert_code 0
  order_id="$(json_get '.data.orderId')"
  poll_until_pending_pay "${order_id}" 15
  pay_order "${order_id}"
  assert_http_2xx
  assert_code 0
  get_order "${order_id}"
  assert_code 0
  status="$(json_get '.data.status')"
  assert_eq "order.status" "PAID" "${status}"
  log "Main flow OK orderId=${order_id}"
}

log "Acceptance suite BASE_URL=${BASE_URL}  UI_URL=${UI_URL}"

run_case "health+main-flow" main_flow
run_case "accept-idempotent" "${SCRIPT_DIR}/accept-idempotent.sh"
run_case "accept-stock-insufficient" "${SCRIPT_DIR}/accept-stock-insufficient.sh"
run_case "accept-activity-not-started" "${SCRIPT_DIR}/accept-activity-not-started.sh"

# Optional Kafka-down smoke (async hot path; skipped unless RUN_KAFKA_CIRCUIT=1)
if [[ "${RUN_KAFKA_CIRCUIT:-0}" == "1" && -x "${SCRIPT_DIR}/accept-kafka-circuit.sh" ]]; then
  run_case "accept-kafka-circuit" "${SCRIPT_DIR}/accept-kafka-circuit.sh"
fi

log ""
if (( FAILED != 0 )); then
  die "Acceptance FAILED"
fi
log "Acceptance ALL PASSED"
