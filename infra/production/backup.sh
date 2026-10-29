#!/usr/bin/env bash
# Backs up this install's databases (and, on request, the document files).
# Run on the Docker server, in infra/production. deploy.sh runs it before every
# upgrade; run it from cron too (e.g. nightly) for routine backups.
#
#   ./backup.sh [--label NAME] [--with-files] [--keep N]
#
# Writes $BACKUP_DIR/<UTC timestamp>-<label>/ (default ./backups, mode 700):
#   dms_core_db.dump, keycloak_db.dump   pg_dump custom format, checked readable
#   documents_data.tgz, seaweedfs_data.tgz   only with --with-files
#   MANIFEST                             when, which release, sha256 of each file
# and keeps the newest N backups (BACKUP_KEEP, default 7), removing older ones.
# The last line printed is BACKUP_PATH=<directory>.
#
# Works with the bundled PostgreSQL (via its container) and with the client's own
# (RDS, Azure, ...) through a throwaway postgres client container that connects
# with the same DB_* settings the stack uses. A backup is not trusted until it has
# been restored once: see restore.sh and docs/operations.md.
#
# Files (--with-files) are the documents_data volume and the bundled SeaweedFS
# volume. Documents kept in the client's own S3 / Azure Blob are NOT copied here -
# back those up with the provider's own tools (versioning, replication).
set -euo pipefail

cd "$(dirname "$0")"
umask 077

BACKUP_DIR="${BACKUP_DIR:-./backups}"
KEEP="${BACKUP_KEEP:-7}"
LABEL=manual
WITH_FILES=0
SECRETS_ENV_FILE="${SECRETS_ENV_FILE:-/dev/shm/oakdms/secrets.env}"
PG_CLIENT_IMAGE="${PG_CLIENT_IMAGE:-postgres:18-alpine}"

log()  { printf '[backup] %s\n' "$*"; }
fail() { printf '[backup] ERROR: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --label) LABEL="$2"; shift 2 ;;
    --with-files) WITH_FILES=1; shift ;;
    --keep) KEEP="$2"; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) fail "unknown option $1" ;;
  esac
done
[[ "$LABEL" =~ ^[A-Za-z0-9._-]+$ ]] || fail "--label: letters, digits . _ - only"
[[ "$KEEP" =~ ^[0-9]+$ ]] && [ "$KEEP" -ge 1 ] || fail "--keep must be a number >= 1"
[ -f .env ] || fail ".env not found in $(pwd)"

dc() {
  if [ -f "$SECRETS_ENV_FILE" ]; then docker compose --env-file .env --env-file "$SECRETS_ENV_FILE" "$@"
  else docker compose "$@"; fi
}
# A setting from the secrets file (if any), then .env; surrounding quotes removed.
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
  else printf 'postgres,redis,rabbitmq,seaweedfs,mailpit,clamav,gotenberg'; fi
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

# pgdo <user> <password> <tool> <args...>: run a PostgreSQL client tool against this
# install's database server; stdin and stdout pass through.
pgdo() {
  local user="$1" pass="$2" tool="$3"; shift 3
  if bundled postgres; then
    dc exec -T postgres "$tool" -U postgres "$@"
  else
    # the password goes in by name (-e PGPASSWORD), never on the command line
    PGPASSWORD="$pass" PGSSLMODE="$SSLMODE" docker run --rm -i -e PGPASSWORD -e PGSSLMODE \
      "$PG_CLIENT_IMAGE" "$tool" -h "$DB_HOST" -p "$DB_PORT" -U "$user" "$@"
  fi
}

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
dest="$BACKUP_DIR/$stamp-$LABEL"
mkdir -p "$dest"; chmod 700 "$BACKUP_DIR" "$dest" 2>/dev/null || true
log "backing up to $dest ($( bundled postgres && echo 'bundled PostgreSQL' || echo "PostgreSQL at $DB_HOST:$DB_PORT" ))"

cleanup_failed() { local rc=$?; trap - ERR; rm -rf "$dest"; printf '[backup] ERROR: failed (exit %s) - the partial backup was removed
' "$rc" >&2; exit "$rc"; }
trap 'cleanup_failed' ERR

for spec in "$CORE_DB:$CORE_USER:$CORE_PASS" "$KC_DB:$KC_USER:$KC_PASS"; do
  IFS=: read -r db user pass <<<"$spec"
  pgdo "$user" "$pass" pg_dump -Fc "$db" > "$dest/$db.dump" || fail "pg_dump of $db failed"
  [ -s "$dest/$db.dump" ] || fail "$db.dump is empty"
  # restore --list reads the whole archive's table of contents: catches truncation
  pgdo "$user" "$pass" pg_restore --list < "$dest/$db.dump" >/dev/null || fail "$db.dump is not a readable archive"
  log "$db: $(du -h "$dest/$db.dump" | cut -f1)"
done

if [ "$WITH_FILES" = 1 ]; then
  project="$(get_var COMPOSE_PROJECT_NAME || echo dms)"
  abs="$(cd "$dest" && pwd)"
  for vol in documents_data seaweedfs_data; do
    [ "$vol" = seaweedfs_data ] && ! bundled seaweedfs && continue
    docker volume inspect "${project}_${vol}" >/dev/null 2>&1 || { log "volume ${project}_${vol} not found - skipped"; continue; }
    docker run --rm -v "${project}_${vol}:/data:ro" -v "$abs:/backup" alpine tar czf "/backup/${vol}.tgz" -C /data .
    log "$vol: $(du -h "$dest/${vol}.tgz" | cut -f1)"
  done
fi

{
  echo "created=$stamp"
  echo "label=$LABEL"
  echo "image_tag=$(get_var IMAGE_TAG || echo unknown)"
  echo "databases=$CORE_DB $KC_DB"
  echo "postgres=$( bundled postgres && echo bundled || echo "$DB_HOST:$DB_PORT" )"
  ( cd "$dest" && shopt -s nullglob && files=(*.dump *.tgz) && sha256sum -- "${files[@]}" | sed 's/^/sha256 /' )
} > "$dest/MANIFEST"
trap - ERR

# retention: newest $KEEP stay
mapfile -t all < <(ls -1d "$BACKUP_DIR"/[0-9]*T[0-9]*Z-* 2>/dev/null | sort)
if [ "${#all[@]}" -gt "$KEEP" ]; then
  for old in "${all[@]:0:${#all[@]}-KEEP}"; do rm -rf "$old"; log "removed old backup $old"; done
fi

log "done."
echo "BACKUP_PATH=$(cd "$dest" && pwd)"
