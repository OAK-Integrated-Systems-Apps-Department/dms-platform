#!/usr/bin/env bash
# Restores the databases from a backup made by backup.sh. Destructive: it REPLACES
# the current data. Run on the Docker server, in infra/production.
#
#   ./restore.sh --list                     show the available backups
#   ./restore.sh <backup-dir> [--yes]       restore both databases from it
#
# Steps: stop the application containers (the database stays up) -> replace each
# database from its .dump -> tell you which release to start again.
#   bundled PostgreSQL   each database is dropped and recreated, then loaded
#   the client's own     the objects are dropped and recreated in place (the
#                        server-level database is not dropped - managed services
#                        usually do not allow it)
# The schema in a backup belongs to the release that was running when it was taken
# (image_tag in its MANIFEST). Start THAT release afterwards - ./deploy.sh <tag> -
# or the application will see a schema it does not match.
#
# Document FILES are not restored here (documents_data.tgz is a plain tar:
# docker run --rm -v <project>_documents_data:/data -v <dir>:/b alpine \
#   tar xzf /b/documents_data.tgz -C /data). Practise a restore on a spare
# server before you need one: docs/operations.md.
set -euo pipefail

cd "$(dirname "$0")"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
SECRETS_ENV_FILE="${SECRETS_ENV_FILE:-/dev/shm/oakdms/secrets.env}"
PG_CLIENT_IMAGE="${PG_CLIENT_IMAGE:-postgres:18-alpine}"

log()  { printf '[restore] %s\n' "$*"; }
fail() { printf '[restore] ERROR: %s\n' "$*" >&2; exit 1; }

if [ "${1:-}" = "--list" ]; then
  ls -1d "$BACKUP_DIR"/[0-9]*T[0-9]*Z-* 2>/dev/null | sort -r | while read -r d; do
    printf '%s  %s  %s\n' "$d" "$(grep '^image_tag=' "$d/MANIFEST" 2>/dev/null | cut -d= -f2)" "$(du -sh "$d" | cut -f1)"
  done
  exit 0
fi
[ $# -ge 1 ] || { sed -n '2,12p' "$0"; exit 2; }
SRC="$1"; shift
YES=0; [ "${1:-}" = "--yes" ] && YES=1
[ -f "$SRC/MANIFEST" ] || fail "$SRC is not a backup directory (no MANIFEST)"
[ -f .env ] || fail ".env not found in $(pwd)"

dc() {
  if [ -f "$SECRETS_ENV_FILE" ]; then docker compose --env-file .env --env-file "$SECRETS_ENV_FILE" "$@"
  else docker compose "$@"; fi
}
get_var() {
  local name="$1" f v
  for f in "$SECRETS_ENV_FILE" .env; do
    [ -f "$f" ] || continue
    v="$(grep -E "^${name}=" "$f" | tail -n1 | cut -d= -f2- | tr -d '\r' || true)"
    if [ -n "$v" ]; then v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"; printf '%s' "$v"; return 0; fi
  done
  return 1
}
profiles() {
  if [ -n "${COMPOSE_PROFILES+x}" ]; then printf '%s' "$COMPOSE_PROFILES"
  elif grep -qE '^COMPOSE_PROFILES=' .env; then get_var COMPOSE_PROFILES || true
  else printf 'postgres,redis,rabbitmq,seaweedfs,clamav,gotenberg'; fi
}
bundled() { [[ ",$(profiles)," == *",$1,"* ]]; }

DB_HOST="$(get_var DB_HOST || echo postgres)"; DB_PORT="$(get_var DB_PORT || echo 5432)"
CORE_DB="$(get_var DMS_CORE_DB_NAME || echo dms_core_db)"
CORE_USER="$(get_var DMS_CORE_DB_USER || echo dms_core_user)"
CORE_PASS="$(get_var DMS_CORE_DB_PASSWORD || true)"
KC_DB="$(get_var KEYCLOAK_DB_NAME || echo keycloak_db)"
KC_USER="$(get_var KEYCLOAK_DB_USER || echo postgres)"
KC_PASS="$(get_var KEYCLOAK_DB_PASSWORD || get_var POSTGRES_PASSWORD || true)"
SSLMODE="$(get_var DB_SSLMODE || echo prefer)"
for n in "$CORE_DB" "$KC_DB"; do [[ "$n" =~ ^[A-Za-z0-9_]+$ ]] || fail "unsafe database name: $n"; done

pgdo() {
  local user="$1" pass="$2" tool="$3"; shift 3
  if bundled postgres; then
    dc exec -T postgres "$tool" -U postgres "$@"
  else
    PGPASSWORD="$pass" PGSSLMODE="$SSLMODE" docker run --rm -i -e PGPASSWORD -e PGSSLMODE \
      "$PG_CLIENT_IMAGE" "$tool" -h "$DB_HOST" -p "$DB_PORT" -U "$user" "$@"
  fi
}

tag="$(grep '^image_tag=' "$SRC/MANIFEST" | cut -d= -f2)"
echo "Backup:   $SRC"
echo "Taken at: $(grep '^created=' "$SRC/MANIFEST" | cut -d= -f2)   release: $tag"
echo "This REPLACES the data in: $CORE_DB, $KC_DB ($( bundled postgres && echo 'bundled PostgreSQL' || echo "$DB_HOST:$DB_PORT" ))"
if [ "$YES" != 1 ]; then
  read -r -p "Type 'restore' to continue: " answer
  [ "$answer" = restore ] || fail "not confirmed - nothing changed"
fi

# the dump files must be intact before anything is dropped
( cd "$SRC" && grep '^sha256 ' MANIFEST | sed 's/^sha256 //' | sha256sum -c --quiet ) || fail "a file in $SRC does not match its checksum - nothing changed"

log "stopping the application containers"
dc stop frontend dms-core ocr-worker keycloak-bootstrap keycloak >/dev/null 2>&1 || true

restore_one() {
  local db="$1" owner="$2" pass="$3" dump="$SRC/$1.dump"
  [ -s "$dump" ] || fail "$dump is missing"
  if bundled postgres; then
    pgdo postgres "" psql -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $db WITH (FORCE)"
    pgdo postgres "" psql -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $db OWNER $owner"
    if [ "$owner" = postgres ]; then
      pgdo postgres "" pg_restore --no-owner -d "$db" < "$dump"
    else
      pgdo postgres "" pg_restore --no-owner --role="$owner" -d "$db" < "$dump"
    fi
  else
    pgdo "$owner" "$pass" pg_restore --clean --if-exists --no-owner -d "$db" < "$dump"
  fi
  log "restored $db"
}
restore_one "$CORE_DB" "$CORE_USER" "$CORE_PASS"
restore_one "$KC_DB" "$KC_USER" "$KC_PASS"

log "done. The application containers are stopped. Start the release these backups belong to:"
echo "    ./deploy.sh $tag"
