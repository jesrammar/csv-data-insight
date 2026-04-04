#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"

usage() {
  echo "Usage:"
  echo "  $0 --db <backup.sql.gz>"
  echo "  $0 --storage <backend-storage.tar.gz>"
  echo "  $0 --pgdata <pgdata.tar.gz>"
  echo ""
  echo "Env:"
  echo "  COMPOSE_FILE (default docker-compose.prod.yml)"
  exit 2
}

DB_IN=""
ST_IN=""
PGDATA_IN=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --db) DB_IN="${2:-}"; shift 2 ;;
    --storage) ST_IN="${2:-}"; shift 2 ;;
    --pgdata) PGDATA_IN="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

if [[ -z "$DB_IN" && -z "$ST_IN" && -z "$PGDATA_IN" ]]; then usage; fi
if [[ -n "$DB_IN" && ! -f "$DB_IN" ]]; then echo "DB backup not found: $DB_IN" >&2; exit 1; fi
if [[ -n "$ST_IN" && ! -f "$ST_IN" ]]; then echo "Storage backup not found: $ST_IN" >&2; exit 1; fi
if [[ -n "$PGDATA_IN" && ! -f "$PGDATA_IN" ]]; then echo "pgdata backup not found: $PGDATA_IN" >&2; exit 1; fi

echo "[restore] $(date -Is) start"

if [[ -n "$DB_IN" ]]; then
  echo "[restore] DB <- $DB_IN"
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T postgres \
    psql -U enterpriseiq -d postgres -v ON_ERROR_STOP=1 \
    -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='enterpriseiq' AND pid <> pg_backend_pid();" >/dev/null
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T postgres \
    psql -U enterpriseiq -d postgres -v ON_ERROR_STOP=1 \
    -c "DROP DATABASE IF EXISTS enterpriseiq;" >/dev/null
  docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T postgres \
    psql -U enterpriseiq -d postgres -v ON_ERROR_STOP=1 \
    -c "CREATE DATABASE enterpriseiq;" >/dev/null

  gunzip -c "$DB_IN" | docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T postgres \
    psql -U enterpriseiq -d enterpriseiq -v ON_ERROR_STOP=1 >/dev/null
fi

if [[ -n "$ST_IN" ]]; then
  VOL_NAME="$(docker volume ls -q --filter label=com.docker.compose.project --filter label=com.docker.compose.volume=backend-storage | head -n 1 || true)"
  if [[ -z "${VOL_NAME}" ]]; then
    PROJECT="$(basename "$ROOT_DIR" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_')"
    VOL_NAME="${PROJECT}_backend-storage"
  fi
  echo "[restore] storage volume=$VOL_NAME <- $ST_IN"
  docker run --rm \
    -v "${VOL_NAME}:/data" \
    -v "$(cd "$(dirname "$ST_IN")" && pwd):/backups:ro" \
    alpine:3.20 \
    sh -lc "rm -rf /data/* && cd /data && tar -xzf /backups/$(basename "$ST_IN")"
fi

if [[ -n "$PGDATA_IN" ]]; then
  PG_VOL="$(docker volume ls -q --filter label=com.docker.compose.project --filter label=com.docker.compose.volume=pgdata | head -n 1 || true)"
  if [[ -z "${PG_VOL}" ]]; then
    PROJECT="$(basename "$ROOT_DIR" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_')"
    PG_VOL="${PROJECT}_pgdata"
  fi
  echo "[restore] pgdata volume=$PG_VOL <- $PGDATA_IN"
  echo "[restore] NOTE: stop postgres before restoring pgdata (docker compose stop postgres)."
  docker run --rm \
    -v "${PG_VOL}:/data" \
    -v "$(cd "$(dirname "$PGDATA_IN")" && pwd):/backups:ro" \
    alpine:3.20 \
    sh -lc "rm -rf /data/* && cd /data && tar -xzf /backups/$(basename "$PGDATA_IN")"
fi

echo "[restore] $(date -Is) done"
