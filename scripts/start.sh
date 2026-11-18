#!/usr/bin/env bash
# Start seckill MVP local stack (MySQL / Redis / Kafka / App / Frontend)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker/docker-compose.yml"
COMPOSE_PROJECT="seckill"

log() { printf '%s\n' "$*"; }
err() { printf 'ERROR: %s\n' "$*" >&2; }

print_docker_guide() {
  cat <<'EOF'
Docker is not installed (or not on PATH).

On macOS (pick one):
  A) Colima (lightweight):
       brew install colima docker docker-compose
       colima start --cpu 2 --memory 6
  B) Docker Desktop:
       brew install --cask docker
       open -a Docker

Then re-run:
  ./scripts/start.sh

Docs: https://docs.docker.com/desktop/setup/install/mac-install/
EOF
}

ensure_docker_daemon() {
  if docker info >/dev/null 2>&1; then
    return 0
  fi
  if command -v colima >/dev/null 2>&1; then
    log "Docker daemon down; starting Colima (2C6G)..."
    colima start --cpu 2 --memory 6 || true
    docker context use colima >/dev/null 2>&1 || true
  fi
  if ! docker info >/dev/null 2>&1; then
    err "Docker CLI is installed but the daemon is not reachable."
    cat <<'EOF'
On macOS:
  colima start --cpu 2 --memory 6
  # or: open -a Docker

Then re-run:
  ./scripts/start.sh
EOF
    exit 1
  fi
}

require_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    print_docker_guide
    exit 1
  fi
  ensure_docker_daemon
  if ! docker compose version >/dev/null 2>&1; then
    err "docker compose plugin is required (Docker Compose V2)."
    exit 1
  fi
}

compose() {
  docker compose -f "${COMPOSE_FILE}" -p "${COMPOSE_PROJECT}" "$@"
}

service_health() {
  local service="$1"
  local cid
  cid="$(compose ps -q "${service}" 2>/dev/null || true)"
  if [[ -z "${cid}" ]]; then
    echo "missing"
    return
  fi
  docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${cid}" 2>/dev/null || echo "unknown"
}

wait_for_service() {
  local service="$1"
  local timeout="${2:-180}"
  local elapsed=0
  local status=""

  log "Waiting for ${service} to become healthy (timeout ${timeout}s)..."
  while (( elapsed < timeout )); do
    status="$(service_health "${service}")"
    if [[ "${status}" == "healthy" ]]; then
      log "  ${service}: healthy"
      return 0
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
  err "${service} did not become healthy within ${timeout}s (last status: ${status:-unknown})"
  compose ps "${service}" || true
  return 1
}

main() {
  require_docker
  cd "${ROOT_DIR}"

  log "Repo root: ${ROOT_DIR}"
  log "Starting stack via docker compose..."
  compose up -d --build

  wait_for_service mysql 180
  wait_for_service redis 120
  wait_for_service kafka 180
  wait_for_service app 300
  wait_for_service frontend 120

  cat <<'EOF'

============================================================
Seckill MVP stack is up.

Next steps:
  ./scripts/demo.sh          # smoke / demo flow (BASE_URL=18178)
  ./scripts/accept.sh        # acceptance checks
  ./scripts/bench-seckill.sh # load / seckill bench (default PARALLEL=500 ≤ Sentinel QPS)

Endpoints:
  UI     http://localhost:18179  (/api → app:18178)
  App    http://localhost:18178  (actuator /api)
  MySQL  localhost:3306  (root/root, db=seckill, user=seckill/seckill)
  Redis  localhost:6379  (no password)
  Kafka  localhost:9092  (PLAINTEXT, topic seckill.stock.deduct x3; async produce)

Defaults: SECKILL_QPS=500 · PAY_WINDOW_MINUTES=1 · Kafka hot-path sendAsync
============================================================
EOF
}

main "$@"
