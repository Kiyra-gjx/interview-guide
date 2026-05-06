#!/usr/bin/env bash
set -euo pipefail

# Interview Agent local infrastructure bootstrap for WSL2 + Docker Desktop.
# This script manages PostgreSQL(pgvector) + Redis(auth) + S3-compatible storage.

STACK_NAME="${STACK_NAME:-agent-dev}"
NETWORK_NAME="${NETWORK_NAME:-${STACK_NAME}-net}"

POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-${STACK_NAME}-postgres}"
REDIS_CONTAINER="${REDIS_CONTAINER:-${STACK_NAME}-redis}"
STORAGE_CONTAINER="${STORAGE_CONTAINER:-${STACK_NAME}-storage}"

POSTGRES_VOLUME="${POSTGRES_VOLUME:-${STACK_NAME}-postgres-data}"
REDIS_VOLUME="${REDIS_VOLUME:-${STACK_NAME}-redis-data}"
STORAGE_VOLUME="${STORAGE_VOLUME:-${STACK_NAME}-storage-data}"

POSTGRES_IMAGE="${POSTGRES_IMAGE:-pgvector/pgvector:pg16}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:7}"
STORAGE_PROVIDER="${STORAGE_PROVIDER:-rustfs}" # rustfs | minio
STORAGE_IMAGE_MINIO="${STORAGE_IMAGE_MINIO:-minio/minio:latest}"
STORAGE_IMAGE_RUSTFS="${STORAGE_IMAGE_RUSTFS:-rustfs/rustfs:latest}"
MC_IMAGE="${MC_IMAGE:-minio/mc:latest}"

POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_USER="${POSTGRES_USER:-postgres}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-password}"
POSTGRES_DB="${POSTGRES_DB:-interview_agent}"

REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PASSWORD="${REDIS_PASSWORD:-123456}"

APP_STORAGE_ENDPOINT="${APP_STORAGE_ENDPOINT:-http://localhost:9000}"
APP_STORAGE_ACCESS_KEY="${APP_STORAGE_ACCESS_KEY:-minioadmin}"
APP_STORAGE_SECRET_KEY="${APP_STORAGE_SECRET_KEY:-minioadmin}"
APP_STORAGE_BUCKET="${APP_STORAGE_BUCKET:-interview-agent}"
APP_STORAGE_REGION="${APP_STORAGE_REGION:-us-east-1}"
STORAGE_API_PORT="${STORAGE_API_PORT:-9000}"
STORAGE_CONSOLE_PORT="${STORAGE_CONSOLE_PORT:-9001}"

WAIT_SECONDS="${WAIT_SECONDS:-90}"

log() {
  printf '[infra] %s\n' "$*"
}

err() {
  printf '[infra][error] %s\n' "$*" >&2
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    err "Missing required command: $1"
    exit 1
  fi
}

container_exists() {
  docker ps -a --format '{{.Names}}' | grep -Fxq "$1"
}

remove_container_if_exists() {
  local name="$1"
  if container_exists "$name"; then
    log "Removing existing container: $name"
    docker rm -f "$name" >/dev/null
  fi
}

ensure_network() {
  if ! docker network inspect "$NETWORK_NAME" >/dev/null 2>&1; then
    log "Creating network: $NETWORK_NAME"
    docker network create "$NETWORK_NAME" >/dev/null
  fi
}

ensure_volumes() {
  docker volume create "$POSTGRES_VOLUME" >/dev/null
  docker volume create "$REDIS_VOLUME" >/dev/null
  docker volume create "$STORAGE_VOLUME" >/dev/null
}

wait_for_postgres() {
  local timeout_at=$((SECONDS + WAIT_SECONDS))
  while ((SECONDS < timeout_at)); do
    if docker exec "$POSTGRES_CONTAINER" pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_for_redis() {
  local timeout_at=$((SECONDS + WAIT_SECONDS))
  while ((SECONDS < timeout_at)); do
    if docker exec "$REDIS_CONTAINER" redis-cli -a "$REDIS_PASSWORD" ping 2>/dev/null | grep -q PONG; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_for_storage() {
  local timeout_at=$((SECONDS + WAIT_SECONDS))
  while ((SECONDS < timeout_at)); do
    if docker run --rm --network "$NETWORK_NAME" "$MC_IMAGE" \
      alias set local "http://${STORAGE_CONTAINER}:9000" "$APP_STORAGE_ACCESS_KEY" "$APP_STORAGE_SECRET_KEY" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

init_bucket() {
  docker run --rm --network "$NETWORK_NAME" --entrypoint /bin/sh "$MC_IMAGE" -c \
    "set -e; \
     mc alias set local http://${STORAGE_CONTAINER}:9000 ${APP_STORAGE_ACCESS_KEY} ${APP_STORAGE_SECRET_KEY}; \
     mc ls local/${APP_STORAGE_BUCKET} >/dev/null 2>&1 || mc mb local/${APP_STORAGE_BUCKET}; \
     mc anonymous set download local/${APP_STORAGE_BUCKET}"
}

start_postgres() {
  log "Starting PostgreSQL (pgvector): $POSTGRES_CONTAINER"
  docker run -d \
    --name "$POSTGRES_CONTAINER" \
    --restart unless-stopped \
    --network "$NETWORK_NAME" \
    -p "${POSTGRES_PORT}:5432" \
    -e POSTGRES_USER="$POSTGRES_USER" \
    -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
    -e POSTGRES_DB="$POSTGRES_DB" \
    -v "${POSTGRES_VOLUME}:/var/lib/postgresql/data" \
    "$POSTGRES_IMAGE" >/dev/null

  if ! wait_for_postgres; then
    err "PostgreSQL did not become ready in ${WAIT_SECONDS}s."
    docker logs --tail 100 "$POSTGRES_CONTAINER" || true
    exit 1
  fi

  log "Ensuring pgvector extension exists in database: $POSTGRES_DB"
  docker exec "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -c "CREATE EXTENSION IF NOT EXISTS vector;" >/dev/null
}

start_redis() {
  log "Starting Redis (with password): $REDIS_CONTAINER"
  docker run -d \
    --name "$REDIS_CONTAINER" \
    --restart unless-stopped \
    --network "$NETWORK_NAME" \
    -p "${REDIS_PORT}:6379" \
    -v "${REDIS_VOLUME}:/data" \
    "$REDIS_IMAGE" \
    redis-server --appendonly yes --requirepass "$REDIS_PASSWORD" --protected-mode no >/dev/null

  if ! wait_for_redis; then
    err "Redis did not become ready in ${WAIT_SECONDS}s."
    docker logs --tail 100 "$REDIS_CONTAINER" || true
    exit 1
  fi
}

start_storage() {
  case "$STORAGE_PROVIDER" in
    rustfs)
      log "Starting RustFS: $STORAGE_CONTAINER"
      docker run -d \
        --name "$STORAGE_CONTAINER" \
        --restart unless-stopped \
        --network "$NETWORK_NAME" \
        -p "${STORAGE_API_PORT}:9000" \
        -e RUSTFS_ACCESS_KEY="$APP_STORAGE_ACCESS_KEY" \
        -e RUSTFS_SECRET_KEY="$APP_STORAGE_SECRET_KEY" \
        -e RUSTFS_DEFAULT_REGION="$APP_STORAGE_REGION" \
        -v "${STORAGE_VOLUME}:/data" \
        "$STORAGE_IMAGE_RUSTFS" >/dev/null
      ;;
    minio)
      log "Starting MinIO: $STORAGE_CONTAINER"
      docker run -d \
        --name "$STORAGE_CONTAINER" \
        --restart unless-stopped \
        --network "$NETWORK_NAME" \
        -p "${STORAGE_API_PORT}:9000" \
        -p "${STORAGE_CONSOLE_PORT}:9001" \
        -e MINIO_ROOT_USER="$APP_STORAGE_ACCESS_KEY" \
        -e MINIO_ROOT_PASSWORD="$APP_STORAGE_SECRET_KEY" \
        -e MINIO_REGION_NAME="$APP_STORAGE_REGION" \
        -v "${STORAGE_VOLUME}:/data" \
        "$STORAGE_IMAGE_MINIO" server /data --console-address ":9001" >/dev/null
      ;;
    *)
      err "Unsupported STORAGE_PROVIDER: ${STORAGE_PROVIDER}. Use rustfs or minio."
      exit 1
      ;;
  esac

  if ! wait_for_storage; then
    err "Storage service did not become ready in ${WAIT_SECONDS}s."
    docker logs --tail 100 "$STORAGE_CONTAINER" || true
    exit 1
  fi

  log "Creating bucket and setting anonymous download policy: $APP_STORAGE_BUCKET"
  init_bucket
}

print_env() {
  local wsl_ip
  wsl_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  cat <<EOF
POSTGRES_HOST=localhost
POSTGRES_PORT=${POSTGRES_PORT}
POSTGRES_DB=${POSTGRES_DB}
POSTGRES_USER=${POSTGRES_USER}
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}

REDIS_HOST=localhost
REDIS_PORT=${REDIS_PORT}
REDIS_PASSWORD=${REDIS_PASSWORD}

APP_STORAGE_ENDPOINT=${APP_STORAGE_ENDPOINT}
APP_STORAGE_ACCESS_KEY=${APP_STORAGE_ACCESS_KEY}
APP_STORAGE_SECRET_KEY=${APP_STORAGE_SECRET_KEY}
APP_STORAGE_BUCKET=${APP_STORAGE_BUCKET}
APP_STORAGE_REGION=${APP_STORAGE_REGION}

# If localhost access from Windows fails, try WSL2 IP below instead:
# POSTGRES_HOST=${wsl_ip}
# REDIS_HOST=${wsl_ip}
# APP_STORAGE_ENDPOINT=http://${wsl_ip}:${STORAGE_API_PORT}
EOF
}

check() {
  log "Checking PostgreSQL extension..."
  docker exec "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
    "SELECT extname FROM pg_extension WHERE extname='vector';" | grep -qx "vector"
  log "PostgreSQL + pgvector is healthy."

  log "Checking Redis auth..."
  docker exec "$REDIS_CONTAINER" redis-cli -a "$REDIS_PASSWORD" ping | grep -q PONG
  log "Redis is healthy."

  log "Checking storage bucket..."
  docker run --rm --network "$NETWORK_NAME" --entrypoint /bin/sh "$MC_IMAGE" -c \
    "set -e; \
     mc alias set local http://${STORAGE_CONTAINER}:9000 ${APP_STORAGE_ACCESS_KEY} ${APP_STORAGE_SECRET_KEY} >/dev/null; \
     mc ls local/${APP_STORAGE_BUCKET} >/dev/null"
  log "Storage bucket is healthy."
}

status() {
  docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' | \
    grep -E "(${POSTGRES_CONTAINER}|${REDIS_CONTAINER}|${STORAGE_CONTAINER})|NAMES" || true
}

logs() {
  docker logs -f "$POSTGRES_CONTAINER" &
  local p1=$!
  docker logs -f "$REDIS_CONTAINER" &
  local p2=$!
  docker logs -f "$STORAGE_CONTAINER" &
  local p3=$!
  wait "$p1" "$p2" "$p3"
}

down() {
  remove_container_if_exists "$POSTGRES_CONTAINER"
  remove_container_if_exists "$REDIS_CONTAINER"
  remove_container_if_exists "$STORAGE_CONTAINER"
  log "Containers removed."
}

purge_data() {
  down
  log "Removing volumes..."
  docker volume rm -f "$POSTGRES_VOLUME" "$REDIS_VOLUME" "$STORAGE_VOLUME" >/dev/null || true
  log "Volumes removed."
}

up() {
  require_cmd docker
  docker info >/dev/null 2>&1 || {
    err "Cannot access Docker daemon. Start Docker Desktop and ensure WSL integration is enabled."
    exit 1
  }

  ensure_network
  ensure_volumes
  down
  start_postgres
  start_redis
  start_storage
  check

  log "Infrastructure is ready."
  if [[ "$STORAGE_PROVIDER" == "minio" ]]; then
    log "Storage Console: http://localhost:${STORAGE_CONSOLE_PORT}"
  fi
  log "Use './scripts/infra-wsl.sh env' to print backend environment variables."
}

usage() {
  cat <<EOF
Usage:
  ./scripts/infra-wsl.sh up           Start all infra services
  ./scripts/infra-wsl.sh down         Stop and remove containers
  ./scripts/infra-wsl.sh purge        Remove containers and data volumes
  ./scripts/infra-wsl.sh status       Show container status
  ./scripts/infra-wsl.sh logs         Tail logs for all services
  ./scripts/infra-wsl.sh check        Run health checks
  ./scripts/infra-wsl.sh env          Print env vars for backend (IDEA)

Optional environment variables:
  STACK_NAME, POSTGRES_PORT, REDIS_PORT, STORAGE_API_PORT, STORAGE_CONSOLE_PORT
  POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_DB
  REDIS_PASSWORD
  STORAGE_PROVIDER (rustfs|minio)
  STORAGE_IMAGE_RUSTFS, STORAGE_IMAGE_MINIO
  APP_STORAGE_ACCESS_KEY, APP_STORAGE_SECRET_KEY, APP_STORAGE_BUCKET, APP_STORAGE_REGION
EOF
}

main() {
  local cmd="${1:-}"
  case "$cmd" in
    up) up ;;
    down) down ;;
    purge) purge_data ;;
    status) status ;;
    logs) logs ;;
    check) check ;;
    env) print_env ;;
    *) usage ;;
  esac
}

main "${1:-}"
