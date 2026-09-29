#!/usr/bin/env bash
# Server-side deploy for infra/production/docker-compose.yml.
# Called by .github/workflows/deploy.yml over SSH, and usable by hand:
#
#   ./deploy.sh <image-tag>    deploy that tag (a full git commit SHA)
#   ./deploy.sh --rollback     redeploy the tag that was live before the current one
#   ./deploy.sh --status       show the deployed tag, history and container health
#
# Flow: pull the new dms-core/frontend images (nothing changes if that fails)
# -> `docker compose up -d` (Compose only recreates containers whose image or
# config changed; unchanged infrastructure keeps running) -> wait for the
# containers' own health checks -> smoke-test through nginx. Only when all
# of that passes is IMAGE_TAG in .env updated. On any failure the previous
# tag is brought back automatically and the script exits non-zero.
#
# Env overrides: HEALTH_TIMEOUT (seconds, default 300), SKIP_NGINX_CHECK=1
# (first install, before nginx is configured).
set -euo pipefail

cd "$(dirname "$0")"

HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-300}"
APP_SERVICES=(dms-core frontend)
HISTORY_FILE=.deploy-history

log()  { printf '[deploy] %s\n' "$*"; }
fail() { printf '[deploy] ERROR: %s\n' "$*" >&2; exit 1; }

[ -f .env ] || fail ".env not found in $(pwd) - create it from .env.example"
if grep -qE '=CHANGE_ME$' .env; then
  fail ".env still has CHANGE_ME placeholders: $(grep -E '=CHANGE_ME$' .env | cut -d= -f1 | tr '\n' ' ')"
fi
if [ "$(stat -c %a .env)" != "600" ]; then
  log "WARNING: .env permissions are $(stat -c %a .env), expected 600 (chmod 600 .env)"
fi

# One deploy at a time.
exec 9>.deploy.lock
flock -n 9 || fail "another deploy is already running"

env_value() { grep -E "^$1=" .env | tail -n1 | cut -d= -f2- | tr -d '\r' || true; }

set_image_tag() {
  if grep -qE '^IMAGE_TAG=' .env; then
    sed -i "s|^IMAGE_TAG=.*|IMAGE_TAG=$1|" .env
  else
    printf 'IMAGE_TAG=%s\n' "$1" >> .env
  fi
}

container_state() {
  local id
  id="$(docker compose ps -a -q "$1" 2>/dev/null | head -n1)"
  [ -n "$id" ] || { echo missing; return; }
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id"
}

wait_for_health() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT)) svc state pending
  while :; do
    pending=""
    for svc in "${APP_SERVICES[@]}"; do
      state="$(container_state "$svc")"
      case "$state" in
        healthy) ;;
        exited|dead|missing) log "$svc is $state"; return 1 ;;
        *) pending="$pending $svc($state)" ;;
      esac
    done
    [ -z "$pending" ] && return 0
    [ "$SECONDS" -ge "$deadline" ] && { log "timed out waiting for:$pending"; return 1; }
    sleep 5
  done
}

smoke_test() {
  [ "${SKIP_NGINX_CHECK:-0}" = "1" ] && { log "SKIP_NGINX_CHECK=1 - skipping nginx checks"; return 0; }
  local url code
  url="$(env_value PUBLIC_URL)"
  [ -n "$url" ] || { log "PUBLIC_URL missing from .env"; return 1; }

  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$url/login" || true)"
  [ "$code" = "200" ] || { log "frontend via nginx: GET $url/login -> $code (want 200)"; return 1; }
  log "frontend via nginx OK ($url/login -> 200)"

  # No cookie -> dms-core itself rejects the refresh. Any 4xx proves nginx
  # routed /api/ to dms-core; 404/5xx would mean routing or backend trouble.
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST "$url/api/v1/auth/refresh" || true)"
  case "$code" in
    400|401|403) log "API via nginx OK (POST /api/v1/auth/refresh -> $code)" ;;
    *) log "API via nginx: POST $url/api/v1/auth/refresh -> $code (want 400/401/403)"; return 1 ;;
  esac

  # The issuer must equal what dms-core validates tokens against.
  if curl -fsS --max-time 10 "$url/realms/oak-dms/.well-known/openid-configuration" \
       | grep -q "\"issuer\":\"$url/realms/oak-dms\""; then
    log "Keycloak via nginx OK (issuer $url/realms/oak-dms)"
  else
    log "Keycloak via nginx: issuer at $url/realms/oak-dms/.well-known/openid-configuration missing or not $url/realms/oak-dms"
    return 1
  fi
}

check_nginx_drift() {
  local installed=/etc/nginx/sites-available/dms
  if [ -f "$installed" ] && ! cmp -s "$installed" nginx/dms.conf; then
    log "NOTE: $installed differs from nginx/dms.conf in this release - review and reinstall it (docs/deployment.md)"
  fi
}

show_diagnostics() {
  docker compose ps -a || true
  for svc in "${APP_SERVICES[@]}"; do
    log "--- last 80 log lines: $svc"
    docker compose logs --no-color --tail=80 "$svc" || true
  done
}

bring_up() {
  local tag="$1"
  IMAGE_TAG="$tag" docker compose up -d
}

deploy() {
  local new="$1" prev
  [[ "$new" =~ ^[A-Za-z0-9._-]+$ ]] || fail "invalid image tag: $new"
  prev="$(env_value IMAGE_TAG)"
  [[ "$prev" == replace-with-* ]] && prev=""

  log "deploying $new (currently: ${prev:-none})"
  IMAGE_TAG="$new" docker compose pull --quiet "${APP_SERVICES[@]}" \
    || fail "could not pull images for $new - nothing was changed"

  # bring_up inside the `if` so a failing `compose up` (e.g. keycloak-bootstrap
  # exiting non-zero) still reaches diagnostics and rollback under `set -e`.
  if bring_up "$new" && wait_for_health && smoke_test; then
    set_image_tag "$new"
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$new" >> "$HISTORY_FILE"
    check_nginx_drift
    log "SUCCESS: $new is live"
    return 0
  fi

  log "FAILED: $new did not become healthy"
  show_diagnostics
  if [ -n "$prev" ] && [ "$prev" != "$new" ]; then
    log "rolling back to $prev"
    if bring_up "$prev" && wait_for_health && smoke_test; then
      log "rollback to $prev succeeded - $new is NOT deployed"
    else
      log "rollback to $prev is ALSO unhealthy - manual intervention needed"
      show_diagnostics
    fi
  else
    log "no previous tag to roll back to"
  fi
  exit 1
}

case "${1:-}" in
  --status)
    log "deployed tag: $(env_value IMAGE_TAG)"
    [ -f "$HISTORY_FILE" ] && { log "recent deploys:"; tail -n 10 "$HISTORY_FILE"; }
    for svc in "${APP_SERVICES[@]}"; do log "$svc: $(container_state "$svc")"; done
    docker compose ps
    ;;
  --rollback)
    current="$(env_value IMAGE_TAG)"
    target="$( [ -f "$HISTORY_FILE" ] && awk '{print $2}' "$HISTORY_FILE" | grep -vxF "$current" | tail -n1 || true)"
    [ -n "$target" ] || fail "no previous tag in $HISTORY_FILE"
    deploy "$target"
    ;;
  ""|-h|--help)
    sed -n '2,20p' "$0"
    ;;
  *)
    deploy "$1"
    ;;
esac
