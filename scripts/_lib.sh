#!/usr/bin/env bash
# Shared helpers for demo / accept / bench scripts.
# Defaults match compose: App :18178, UI :18179, Sentinel SECKILL_QPS=500,
# pay window 1 min (PAY_WINDOW_MINUTES), Kafka async on hot path.
# shellcheck disable=SC2034

: "${BASE_URL:=http://localhost:18178}"
: "${UI_URL:=http://localhost:18179}"
: "${SKU_ID:=1001}"
: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_USER:=seckill}"
: "${MYSQL_PASSWORD:=seckill}"
: "${MYSQL_DB:=seckill}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_DIR="${SCRIPT_DIR}/report"

log()  { printf '%s\n' "$*"; }
err()  { printf 'ERROR: %s\n' "$*" >&2; }
die()  { err "$*"; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

require_cmd() {
  local c
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || die "Required command not found: $c"
  done
}

require_jq() {
  command -v jq >/dev/null 2>&1 || die "jq is required for acceptance scripts. Install jq and retry."
}

# ISO local datetime via python3 (portable macOS/Linux)
iso_now_offset() {
  local seconds="${1:-0}"
  python3 -c "from datetime import datetime,timedelta; print((datetime.now()+timedelta(seconds=int('${seconds}'))).strftime('%Y-%m-%dT%H:%M:%S'))"
}

# curl wrapper: stores body in LAST_BODY, http code in LAST_HTTP
# Usage: api METHOD PATH [curl args...]
# Extra args after PATH are passed to curl (e.g. -H, -d, -b, -c)
api() {
  local method="$1"
  local path="$2"
  shift 2
  local url="${BASE_URL}${path}"
  local tmp
  tmp="$(mktemp)"
  # shellcheck disable=SC2068
  LAST_HTTP="$(curl -sS -o "${tmp}" -w '%{http_code}' -X "${method}" \
    -H 'Content-Type: application/json' -H 'Accept: application/json' \
    "$@" "${url}")" || {
    rm -f "${tmp}"
    die "curl failed: ${method} ${url}"
  }
  LAST_BODY="$(cat "${tmp}")"
  rm -f "${tmp}"
}

json_code() {
  printf '%s' "${LAST_BODY}" | jq -r '.code // empty'
}

json_get() {
  printf '%s' "${LAST_BODY}" | jq -r "$1"
}

assert_http_2xx() {
  case "${LAST_HTTP}" in
    2*) ;;
    *) die "Expected HTTP 2xx, got ${LAST_HTTP}. body=${LAST_BODY}" ;;
  esac
}

assert_code() {
  local expected="$1"
  local actual
  actual="$(json_code)"
  [[ "${actual}" == "${expected}" ]] || die "Expected code=${expected}, got code=${actual}. body=${LAST_BODY}"
}

assert_eq() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  [[ "${actual}" == "${expected}" ]] || die "${label}: expected '${expected}', got '${actual}'"
}

health_check() {
  step "Health check ${BASE_URL}/actuator/health"
  api GET /actuator/health
  assert_http_2xx
  local status
  status="$(printf '%s' "${LAST_BODY}" | jq -r '.status // empty' 2>/dev/null || true)"
  if [[ -n "${status}" && "${status}" != "UP" ]]; then
    die "Actuator health status=${status} body=${LAST_BODY}"
  fi
  log "Health OK (HTTP ${LAST_HTTP})"
}

# Login → sets COOKIE_JAR and USER_ID
login() {
  local jar="${1:-$(mktemp)}"
  COOKIE_JAR="${jar}"
  api POST /api/auth/login -c "${COOKIE_JAR}"
  assert_http_2xx
  assert_code 0
  USER_ID="$(json_get '.data.userId')"
  [[ -n "${USER_ID}" && "${USER_ID}" != "null" ]] || die "login missing userId: ${LAST_BODY}"
  log "Logged in userId=${USER_ID}"
}

# Create activity. Args: name stock [start_offset_sec] [end_offset_sec] [sku] [price] [per_user_limit]
# Default start_offset=+5 so warmup can run before sale opens; end=+1h.
create_activity() {
  local name="$1"
  local stock="$2"
  local start_off="${3:-5}"
  local end_off="${4:-3600}"
  local sku="${5:-${SKU_ID}}"
  local price="${6:-9900}"
  local limit="${7:-1}"
  local start end body
  start="$(iso_now_offset "${start_off}")"
  end="$(iso_now_offset "${end_off}")"
  body="$(jq -n \
    --arg name "${name}" \
    --arg start "${start}" \
    --arg end "${end}" \
    --argjson sku "${sku}" \
    --argjson stock "${stock}" \
    --argjson limit "${limit}" \
    --argjson price "${price}" \
    '{name:$name,startTime:$start,endTime:$end,skuId:$sku,skuName:("SKU-"+($sku|tostring)),totalStock:$stock,perUserLimit:$limit,unitPriceCent:$price}')"
  api POST /api/admin/activities -d "${body}"
  assert_http_2xx
  assert_code 0
  ACTIVITY_ID="$(json_get '.data.activityId')"
  ACTIVITY_SKU="$(json_get '.data.skuId')"
  ACTIVITY_START="$(json_get '.data.startTime')"
  [[ -n "${ACTIVITY_ID}" && "${ACTIVITY_ID}" != "null" ]] || die "create activity failed: ${LAST_BODY}"
  log "Created activityId=${ACTIVITY_ID} skuId=${ACTIVITY_SKU} stock=${stock} start=${ACTIVITY_START}"
}

warmup_activity() {
  local id="${1:-${ACTIVITY_ID}}"
  api POST "/api/admin/activities/${id}/warmup"
  assert_http_2xx
  assert_code 0
  local n
  n="$(json_get '.data')"
  log "Warmup activityId=${id} warmed=${n}"
  if [[ "${n}" == "0" || -z "${n}" || "${n}" == "null" ]]; then
    err "Warmup returned 0 (activity may already have started). Retry with a future startTime."
    return 1
  fi
  return 0
}

# Wait until wall clock is past ACTIVITY_START (LocalDateTime string) + optional buffer seconds
wait_until_started() {
  local start="${1:-${ACTIVITY_START}}"
  local buffer="${2:-1}"
  python3 - "$start" "$buffer" <<'PY'
import sys, time
from datetime import datetime, timedelta
start_s, buf = sys.argv[1], int(sys.argv[2])
# tolerate fractional / offset forms
for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M:%S.%f"):
    try:
        start = datetime.strptime(start_s[:26].rstrip("Z"), fmt)
        break
    except ValueError:
        start = None
else:
    start = datetime.fromisoformat(start_s.replace("Z", ""))
target = start + timedelta(seconds=buf)
now = datetime.now()
wait = (target - now).total_seconds()
if wait > 0:
    print(f"Waiting {wait:.1f}s until activity start+{buf}s...", flush=True)
    time.sleep(wait)
PY
}

place_order() {
  local activity_id="$1"
  local sku_id="$2"
  local request_id="$3"
  local auth_args=()
  if [[ -n "${COOKIE_JAR:-}" && -f "${COOKIE_JAR}" ]]; then
    auth_args=(-b "${COOKIE_JAR}")
  elif [[ -n "${USER_ID:-}" ]]; then
    auth_args=(-H "X-User-Id: ${USER_ID}")
  else
    die "place_order: need COOKIE_JAR or USER_ID"
  fi
  local body
  body="$(jq -n \
    --argjson aid "${activity_id}" \
    --argjson sku "${sku_id}" \
    --arg rid "${request_id}" \
    '{activityId:$aid,skuId:$sku,requestId:$rid,qty:1}')"
  api POST /api/seckill/orders "${auth_args[@]}" -d "${body}"
}

get_order() {
  local order_id="$1"
  local auth_args=()
  if [[ -n "${COOKIE_JAR:-}" && -f "${COOKIE_JAR}" ]]; then
    auth_args=(-b "${COOKIE_JAR}")
  elif [[ -n "${USER_ID:-}" ]]; then
    auth_args=(-H "X-User-Id: ${USER_ID}")
  else
    die "get_order: need COOKIE_JAR or USER_ID"
  fi
  api GET "/api/seckill/orders/${order_id}" "${auth_args[@]}"
}

pay_order() {
  local order_id="$1"
  local auth_args=()
  if [[ -n "${COOKIE_JAR:-}" && -f "${COOKIE_JAR}" ]]; then
    auth_args=(-b "${COOKIE_JAR}")
  elif [[ -n "${USER_ID:-}" ]]; then
    auth_args=(-H "X-User-Id: ${USER_ID}")
  else
    die "pay_order: need COOKIE_JAR or USER_ID"
  fi
  api POST "/api/seckill/orders/${order_id}/pay" "${auth_args[@]}" -d '{}'
}

# Poll until status is PENDING_PAY (or already PAID). Max ~15s.
poll_until_pending_pay() {
  local order_id="$1"
  local max_sec="${2:-15}"
  local elapsed=0
  local status=""
  while (( elapsed < max_sec )); do
    get_order "${order_id}"
    assert_http_2xx
    assert_code 0
    status="$(json_get '.data.status')"
    log "  order ${order_id} status=${status} (${elapsed}s)"
    case "${status}" in
      PENDING_PAY|PAID) ORDER_STATUS="${status}"; return 0 ;;
      PROCESSING) ;;
      *) die "Unexpected order status=${status} while polling" ;;
    esac
    sleep 1
    elapsed=$((elapsed + 1))
  done
  die "Timeout waiting for PENDING_PAY on order ${order_id} (last=${status})"
}

mysql_query() {
  local sql="$1"
  if command -v mysql >/dev/null 2>&1; then
    mysql -h "${MYSQL_HOST}" -P "${MYSQL_PORT}" -u "${MYSQL_USER}" "-p${MYSQL_PASSWORD}" \
      "${MYSQL_DB}" -N -s -e "${sql}" 2>/dev/null
    return $?
  fi
  if command -v docker >/dev/null 2>&1; then
    # NOTE: must be "-u seckill" (not -usekill → user "sekill")
    docker exec seckill-mysql mysql -u seckill -pseckill seckill -N -s -e "${sql}" 2>/dev/null
    return $?
  fi
  return 1
}

count_valid_orders_db() {
  local activity_id="$1"
  mysql_query "SELECT COUNT(*) FROM seckill_order WHERE activity_id=${activity_id} AND status IN ('PROCESSING','PENDING_PAY','PAID');" \
    || echo ""
}
