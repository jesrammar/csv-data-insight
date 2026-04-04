#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
BACKUP_DIR="${BACKUP_DIR:-$ROOT_DIR/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-15}"
INCLUDE_PGDATA_VOLUME="${INCLUDE_PGDATA_VOLUME:-0}"

TS="$(date +"%Y%m%d-%H%M%S")"
mkdir -p "$BACKUP_DIR/db" "$BACKUP_DIR/storage"
mkdir -p "$BACKUP_DIR/pgdata"

echo "[backup] $(date -Is) start"

DB_OUT="$BACKUP_DIR/db/enterpriseiq-$TS.sql.gz"
echo "[backup] db -> $DB_OUT"
docker compose -f "$ROOT_DIR/$COMPOSE_FILE" exec -T postgres \
  pg_dump -U enterpriseiq enterpriseiq \
  | gzip > "$DB_OUT"

resolve_compose_volume() {
  local vol="$1"
  local found
  found="$(docker volume ls -q --filter label=com.docker.compose.project --filter label=com.docker.compose.volume="$vol" | head -n 1 || true)"
  if [[ -n "$found" ]]; then
    echo "$found"
    return
  fi
  local project
  project="$(basename "$ROOT_DIR" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_')"
  echo "${project}_${vol}"
}

VOL_NAME="$(resolve_compose_volume backend-storage)"

ST_OUT="$BACKUP_DIR/storage/backend-storage-$TS.tar.gz"
echo "[backup] storage volume=$VOL_NAME -> $ST_OUT"
docker run --rm \
  -v "${VOL_NAME}:/data:ro" \
  -v "$BACKUP_DIR/storage:/backups" \
  alpine:3.20 \
  sh -lc "cd /data && tar -czf /backups/$(basename "$ST_OUT") ."

if [[ "$INCLUDE_PGDATA_VOLUME" == "1" ]]; then
  PG_VOL="$(resolve_compose_volume pgdata)"
  PG_OUT="$BACKUP_DIR/pgdata/pgdata-$TS.tar.gz"
  echo "[backup] pgdata volume=$PG_VOL -> $PG_OUT"
  docker run --rm \
    -v "${PG_VOL}:/data:ro" \
    -v "$BACKUP_DIR/pgdata:/backups" \
    alpine:3.20 \
    sh -lc "cd /data && tar -czf /backups/$(basename "$PG_OUT") ."
fi

echo "[backup] prune older than ${RETENTION_DAYS}d"
find "$BACKUP_DIR/db" -type f -name "*.gz" -mtime +"$RETENTION_DAYS" -delete || true
find "$BACKUP_DIR/storage" -type f -name "*.gz" -mtime +"$RETENTION_DAYS" -delete || true
find "$BACKUP_DIR/pgdata" -type f -name "*.gz" -mtime +"$RETENTION_DAYS" -delete || true

echo "[backup] $(date -Is) done"
