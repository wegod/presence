#!/usr/bin/env bash
# Happy-path demo: health → login → create → warmup → order → poll → pay → PAID
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "${SCRIPT_DIR}/_lib.sh"

require_cmd curl python3
require_jq

COOKIE_JAR="$(mktemp)"
trap 'rm -f "${COOKIE_JAR}"' EXIT

log "BASE_URL=${BASE_URL}  UI_URL=${UI_URL}"
log "Demo happy path (activity opens shortly after warmup; pay window=1min)"

step "1/8 Health check"
health_check

step "2/8 Login"
login "${COOKIE_JAR}"

step "3/8 Create activity (stock=10, skuId=1001, perUserLimit=1, price=9900)"
# Warmup refuses after startTime; use +5s then wait so sale is live at order time.
# Equivalent intent to "start NOW-1s" once the purchase happens.
create_activity "demo-$(date +%s)" 10 5 3600 1001 9900 1

step "4/8 Warmup"
warmup_activity "${ACTIVITY_ID}"

step "5/8 Wait until activity started, then place order"
wait_until_started "${ACTIVITY_START}" 1
REQUEST_ID="demo-$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')"
place_order "${ACTIVITY_ID}" "${ACTIVITY_SKU}" "${REQUEST_ID}"
assert_http_2xx
assert_code 0
ORDER_ID="$(json_get '.data.orderId')"
log "Placed orderId=${ORDER_ID} requestId=${REQUEST_ID} status=$(json_get '.data.status')"

step "6/8 Poll order until PENDING_PAY (max ~15s)"
poll_until_pending_pay "${ORDER_ID}" 15

step "7/8 Pay"
pay_order "${ORDER_ID}"
assert_http_2xx
assert_code 0
log "Pay response status=$(json_get '.data.status') tradeNo=$(json_get '.data.payTradeNo')"

step "8/8 Assert PAID"
get_order "${ORDER_ID}"
assert_http_2xx
assert_code 0
STATUS="$(json_get '.data.status')"
assert_eq "order.status" "PAID" "${STATUS}"

log ""
log "DEMO OK — orderId=${ORDER_ID} status=PAID"
