#!/usr/bin/env bash
# stock=1, two different users → second gets STOCK_INSUFFICIENT (1003) or SALE_DISABLED (1013).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "${SCRIPT_DIR}/_lib.sh"

require_cmd curl python3
require_jq

JAR1="$(mktemp)"
JAR2="$(mktemp)"
trap 'rm -f "${JAR1}" "${JAR2}"' EXIT

log "BASE_URL=${BASE_URL}"
step "Stock insufficient"

health_check

login "${JAR1}"
USER1="${USER_ID}"
COOKIE_JAR="${JAR1}"

create_activity "accept-stock-$(date +%s)" 1 5 3600 1001 9900 1
warmup_activity "${ACTIVITY_ID}"
wait_until_started "${ACTIVITY_START}" 1

RID1="stock-u1-$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')"
place_order "${ACTIVITY_ID}" "${ACTIVITY_SKU}" "${RID1}"
assert_http_2xx
assert_code 0
ORDER1="$(json_get '.data.orderId')"
log "User1=${USER1} got orderId=${ORDER1}"

login "${JAR2}"
USER2="${USER_ID}"
COOKIE_JAR="${JAR2}"
RID2="stock-u2-$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')"
place_order "${ACTIVITY_ID}" "${ACTIVITY_SKU}" "${RID2}"
assert_http_2xx
CODE2="$(json_code)"
log "User2=${USER2} place code=${CODE2} message=$(json_get '.message')"

if [[ "${CODE2}" != "1003" && "${CODE2}" != "1013" ]]; then
  die "Expected STOCK_INSUFFICIENT(1003) or SALE_DISABLED(1013), got ${CODE2}. body=${LAST_BODY}"
fi

log "accept-stock-insufficient OK (code=${CODE2})"
