#!/usr/bin/env bash
# Same userId + requestId twice → same orderId, only one order.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "${SCRIPT_DIR}/_lib.sh"

require_cmd curl python3
require_jq

COOKIE_JAR="$(mktemp)"
trap 'rm -f "${COOKIE_JAR}"' EXIT

log "BASE_URL=${BASE_URL}"
step "Idempotent place-order"

health_check
login "${COOKIE_JAR}"
create_activity "accept-idem-$(date +%s)" 10 5 3600 1001 9900 1
warmup_activity "${ACTIVITY_ID}"
wait_until_started "${ACTIVITY_START}" 1

REQUEST_ID="idem-$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')"

place_order "${ACTIVITY_ID}" "${ACTIVITY_SKU}" "${REQUEST_ID}"
assert_http_2xx
# First call must succeed (code 0)
assert_code 0
ORDER_ID_1="$(json_get '.data.orderId')"
[[ -n "${ORDER_ID_1}" && "${ORDER_ID_1}" != "null" ]] || die "missing orderId on first place"
log "First place → orderId=${ORDER_ID_1} code=$(json_code)"

place_order "${ACTIVITY_ID}" "${ACTIVITY_SKU}" "${REQUEST_ID}"
assert_http_2xx
CODE2="$(json_code)"
# Replay may be OK(0) with same order, or IDEMPOTENT_REPLAY(1005) with same/none data
if [[ "${CODE2}" != "0" && "${CODE2}" != "1005" ]]; then
  die "Second place expected code 0 or 1005, got ${CODE2}. body=${LAST_BODY}"
fi
ORDER_ID_2="$(json_get '.data.orderId // empty')"
if [[ -n "${ORDER_ID_2}" && "${ORDER_ID_2}" != "null" && "${ORDER_ID_2}" != "" ]]; then
  assert_eq "replay orderId" "${ORDER_ID_1}" "${ORDER_ID_2}"
fi
log "Second place → code=${CODE2} orderId=${ORDER_ID_2:-n/a}"

# Confirm only one order for this user+request via list (or DB)
api GET /api/seckill/orders -b "${COOKIE_JAR}"
assert_http_2xx
assert_code 0
MATCH_COUNT="$(printf '%s' "${LAST_BODY}" | jq --arg rid "${REQUEST_ID}" \
  '[.data[]? | select(.requestId==$rid)] | length')"
assert_eq "orders with requestId" "1" "${MATCH_COUNT}"

# Optional DB check
DB_COUNT="$(mysql_query "SELECT COUNT(*) FROM seckill_order WHERE user_id=${USER_ID} AND request_id='${REQUEST_ID}';" || echo "")"
if [[ -n "${DB_COUNT}" ]]; then
  assert_eq "db order count" "1" "${DB_COUNT}"
  log "DB confirms single order"
fi

log "accept-idempotent OK"
