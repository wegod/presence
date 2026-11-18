#!/usr/bin/env bash
# Activity startTime in the future → ACTIVITY_NOT_STARTED (1001).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "${SCRIPT_DIR}/_lib.sh"

require_cmd curl python3
require_jq

COOKIE_JAR="$(mktemp)"
trap 'rm -f "${COOKIE_JAR}"' EXIT

log "BASE_URL=${BASE_URL}"
step "Activity not started"

health_check
login "${COOKIE_JAR}"

# start in +10 minutes; do not wait
create_activity "accept-not-started-$(date +%s)" 10 600 7200 1001 9900 1
warmup_activity "${ACTIVITY_ID}" || true

RID="not-started-$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())')"
place_order "${ACTIVITY_ID}" "${ACTIVITY_SKU}" "${RID}"
assert_http_2xx
CODE="$(json_code)"
assert_eq "business code" "1001" "${CODE}"
log "Got ACTIVITY_NOT_STARTED as expected: $(json_get '.message')"

log "accept-activity-not-started OK"
