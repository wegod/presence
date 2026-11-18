#!/usr/bin/env bash
# Kafka-down smoke against async hot path (sendAsync, not sync future.get).
#
# Current placeOrder: Kafka submit failure is swallowed — order+outbox stay for
# OutboxCompensate; response is still success (PROCESSING / code 0). Must not be HTTP 5xx.
# Optional: if CB/submit surfaces DEPENDENCY_KAFKA(1009) or SECKILL_STOPPED(1006), that is OK too.
# Restores Kafka on exit.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=_lib.sh
source "${SCRIPT_DIR}/_lib.sh"

require_cmd curl python3 docker
require_jq

COMPOSE=(docker compose -f "${ROOT_DIR}/docker/docker-compose.yml" -p seckill)

restore_kafka() {
  log "Restoring kafka..."
  "${COMPOSE[@]}" start kafka >/dev/null 2>&1 || true
  # wait until broker answers
  for _ in $(seq 1 30); do
    if docker exec seckill-kafka /opt/kafka/bin/kafka-broker-api-versions.sh \
      --bootstrap-server localhost:9092 >/dev/null 2>&1; then
      log "Kafka is up"
      return 0
    fi
    sleep 2
  done
  err "Kafka did not become ready after start (continuing)"
}
trap restore_kafka EXIT

log "BASE_URL=${BASE_URL}"
step "1) Create activity + warmup while Kafka is still up"
COOKIE_JAR="$(mktemp)"
login "${COOKIE_JAR}"
create_activity "accept-kafka-cb-$(date +%s)" 20 3 3600 6001 100 1
warmup_activity "${ACTIVITY_ID}"
wait_until_started "${ACTIVITY_START}" 1

step "2) Stop kafka"
"${COMPOSE[@]}" stop kafka

step "3) Place-order while Kafka down (async path: no 5xx; code 0 and/or 1006/1009)"
OK_SOFT=0
OK_BIZ=0
LAST_CODES=""
for i in $(seq 1 20); do
  USER_ID="$((7100000000 + i))"
  # force header auth (no cookie) so each user is distinct
  unset COOKIE_JAR || true
  RID="kcb-${ACTIVITY_ID}-${i}"
  set +e
  place_order "${ACTIVITY_ID}" "${ACTIVITY_SKU}" "${RID}"
  set -e
  CODE="$(json_code || echo "")"
  LAST_CODES="${LAST_CODES},${CODE}"
  log "try ${i} http=${LAST_HTTP:-?} code=${CODE} msg=$(printf '%s' "${LAST_BODY}" | jq -r '.message // empty' 2>/dev/null || true)"
  if [[ "${LAST_HTTP:-}" =~ ^5 ]]; then
    die "Got HTTP 5xx while Kafka down (want business/soft path). body=${LAST_BODY}"
  fi
  if [[ "${CODE}" == "0" ]]; then
    OK_SOFT=1
  fi
  if [[ "${CODE}" == "1006" || "${CODE}" == "1009" ]]; then
    OK_BIZ=1
  fi
  sleep 0.3
done

restore_kafka
trap - EXIT

if (( OK_SOFT != 1 && OK_BIZ != 1 )); then
  die "Expected code 0 (async soft keep outbox) and/or 1006/1009; codes=${LAST_CODES}"
fi
log "accept-kafka-circuit OK (soft_ok=${OK_SOFT} biz_stop=${OK_BIZ} codes sample=${LAST_CODES})"
