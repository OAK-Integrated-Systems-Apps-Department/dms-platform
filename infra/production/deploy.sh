#!/usr/bin/env bash
# Server-side deploy for infra/production/docker-compose.yml.
# Called by .github/workflows/deploy.yml over SSH, and usable by hand:
#
#   ./deploy.sh <image-tag>    deploy that tag (a full git commit SHA)
#   ./deploy.sh --rollback     redeploy the tag that was live before the current one
#   ./deploy.sh --status       show the deployed tag, history and container health
#
# Flow: pull the new dms-core/frontend/ocr-worker images (nothing changes if that fails)
# -> `docker compose up -d` (Compose only recreates containers whose image or
# config changed; unchanged infrastructure keeps running) -> wait for the
# containers' own health checks -> smoke-test through nginx. Only when all
# of that passes is IMAGE_TAG in .env updated. On any failure the previous
# tag is brought back automatically and the script exits non-zero.
#
# Secrets: if $SECRETS_ENV_FILE exists (default /dev/shm/oakdms/secrets.env,
# RAM only - rendered from OpenBao by the deploy workflow or by
# `bash ../openbao/bao-ops.sh render`), it is passed to Compose AFTER .env,
# so its values win and .env can hold configuration only. Without it, the
# secrets must be in .env as before. See docs/secrets-openbao.md.
#
# Env overrides: HEALTH_TIMEOUT (seconds, default 300), SKIP_NGINX_CHECK=1
# (first install, before nginx is configured), SECRETS_ENV_FILE, SKIP_PULL=1
# (the images are already on this host - an offline release bundle loaded them
# with `docker load`, see install-release.sh).
set -euo pipefail

cd "$(dirname "$0")"

HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-300}"
APP_SERVICES=(dms-core frontend)
# Reported after a deploy but never a reason to roll one back: the worker warms
# two models at start-up, and OCR being down must not undo a good release.
OPTIONAL_SERVICES=(ocr-worker)
HISTORY_FILE=.deploy-history
SECRETS_ENV_FILE="${SECRETS_ENV_FILE:-/dev/shm/oakdms/secrets.env}"

log()  { printf '[deploy] %s\n' "$*"; }
fail() { printf '[deploy] ERROR: %s\n' "$*" >&2; exit 1; }

[ -f .env ] || fail ".env not found in $(pwd) - create it from .env.example"

# Which of the bundled infrastructure services this server runs. Each one can be
# replaced by the client's own (AWS RDS, Azure Database, a managed Redis or
# RabbitMQ, S3 or Azure Blob, a real SMTP relay, ...) by leaving its profile out
# of COMPOSE_PROFILES in .env - see docs/external-services.md. A server whose
# .env predates this setting keeps running everything it ran before.
DEFAULT_PROFILES="postgres,redis,rabbitmq,seaweedfs,mailpit,clamav,gotenberg"
if [ -z "${COMPOSE_PROFILES+x}" ] && ! grep -qE '^COMPOSE_PROFILES=' .env; then
  export COMPOSE_PROFILES="$DEFAULT_PROFILES"
  log "COMPOSE_PROFILES is not set in .env - running every bundled service ($DEFAULT_PROFILES)"
fi
active_profiles() {
  if [ -n "${COMPOSE_PROFILES+x}" ]; then printf '%s' "$COMPOSE_PROFILES"
  else grep -E '^COMPOSE_PROFILES=' .env | tail -n1 | cut -d= -f2- | tr -d "\r\"'"; fi
}

# Secrets this server does not need: those of a bundled service that is switched
# off, and the ones that are optional everywhere (secret-profiles.txt, one
# "<profile> <KEY>" per line; profile "optional" = never required).
unneeded_secrets() {
  local active profile key
  active=",$(active_profiles),"
  [ -f secret-profiles.txt ] || return 0
  while read -r profile key; do
    [ -n "$profile" ] && [[ "$profile" != \#* ]] || continue
    key="${key%$'\r'}"
    if [ "$profile" = optional ] || [[ "$active" != *",$profile,"* ]]; then echo "$key"; fi
  done < secret-profiles.txt
}

# Every Compose call goes through here, so .env and (if present) the
# OpenBao-rendered secrets file are always combined the same way.
dc() {
  if [ -f "$SECRETS_ENV_FILE" ]; then
    docker compose --env-file .env --env-file "$SECRETS_ENV_FILE" "$@"
  else
    docker compose "$@"
  fi
}

defined_keys() { grep -hE '^[A-Za-z_][A-Za-z0-9_]*=' "$@" | cut -d= -f1 | sort -u; }
if [ -f "$SECRETS_ENV_FILE" ]; then
  log "secrets: from OpenBao ($SECRETS_ENV_FILE)"
  if grep -qE "=[\"']?CHANGE_ME[\"']?$" "$SECRETS_ENV_FILE"; then
    fail "$SECRETS_ENV_FILE still has CHANGE_ME values"
  fi
  # A CHANGE_ME left in .env is fine for any key the secrets file supplies.
  placeholders="$(comm -23 <(grep -E '=CHANGE_ME$' .env | cut -d= -f1 | sort -u) <(defined_keys "$SECRETS_ENV_FILE") | grep -vxFf <(unneeded_secrets) || true)"
else
  log "secrets: from .env (no $SECRETS_ENV_FILE)"
  placeholders="$(grep -E '=CHANGE_ME$' .env | cut -d= -f1 | grep -vxFf <(unneeded_secrets) || true)"
fi
[ -z "$placeholders" ] || fail "still CHANGE_ME: $(echo "$placeholders" | tr '\n' ' ')"

# Keys from secret-keys.txt that neither .env nor the secrets file defines.
missing_secrets() {
  local sources=(.env); [ -f "$SECRETS_ENV_FILE" ] && sources+=("$SECRETS_ENV_FILE")
  comm -23 <(grep -vE '^\s*(#|$)' secret-keys.txt | tr -d '\r' | sort -u | grep -vxFf <(unneeded_secrets) || true) <(defined_keys "${sources[@]}")
}
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
  id="$(dc ps -a -q "$1" 2>/dev/null | head -n1)"
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
  dc ps -a || true
  for svc in "${APP_SERVICES[@]}" "${OPTIONAL_SERVICES[@]}"; do
    log "--- last 80 log lines: $svc"
    dc logs --no-color --tail=80 "$svc" || true
  done
}

# An older tag (a rollback target from before the OCR worker existed) has no
# ocr-worker image. Start everything else rather than fail the whole rollback.
# Is the optional services' image available for this tag - pullable, or already
# loaded on this host when SKIP_PULL=1?
optional_available() {
  local tag="$1" img
  if [ "${SKIP_PULL:-0}" = "1" ]; then
    while read -r img; do
      docker image inspect "$img" >/dev/null 2>&1 || return 1
    done < <(IMAGE_TAG="$tag" dc config --images | grep -E "/(${OPTIONAL_SERVICES[*]// /|}):" || true)
    return 0
  fi
  IMAGE_TAG="$tag" dc pull --quiet "${OPTIONAL_SERVICES[@]}" 2>/dev/null
}

bring_up() {
  local tag="$1" services
  if ! optional_available "$tag"; then
    log "no ${OPTIONAL_SERVICES[*]} image for $tag - starting the other services only"
    services="$(IMAGE_TAG="$tag" dc config --services | grep -vxF "${OPTIONAL_SERVICES[0]}" | tr '\n' ' ')"
    # shellcheck disable=SC2086
    IMAGE_TAG="$tag" dc up -d $services
    return
  fi
  IMAGE_TAG="$tag" dc up -d
}

# Informational: wait for the optional services, but only warn if they are not up.
report_optional() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT)) svc state
  for svc in "${OPTIONAL_SERVICES[@]}"; do
    while :; do
      state="$(container_state "$svc")"
      [ "$state" = healthy ] && { log "$svc is healthy"; break; }
      case "$state" in exited|dead|missing) log "WARNING: $svc is $state - the release is live, OCR is not"; break ;; esac
      [ "$SECONDS" -ge "$deadline" ] && { log "WARNING: $svc still $state after ${HEALTH_TIMEOUT}s - the release is live, check OCR"; break; }
      sleep 5
    done
  done
}

# A service left out of COMPOSE_PROFILES must have its replacement configured, or
# the stack would start pointed at a hostname that does not exist.
check_external_services() {
  local active pair profile var problems=""
  active=",$(active_profiles),"
  for pair in postgres:DB_HOST rabbitmq:RABBITMQ_HOST redis:REDIS_HOST clamav:CLAMAV_HOST gotenberg:PREVIEW_CONVERTER_URL; do
    profile="${pair%%:*}"; var="${pair##*:}"
    [[ "$active" == *",$profile,"* ]] || [ -n "$(env_value "$var")" ] || problems="$problems $var"
  done
  if [[ "$active" != *",mailpit,"* ]] && { [ -z "$(env_value SMTP_HOST)" ] || [ "$(env_value SMTP_HOST)" = mailpit ]; }; then
    problems="$problems SMTP_HOST"
  fi
  if [[ "$active" != *",postgres,"* ]] && ! grep -qs '^KEYCLOAK_DB_PASSWORD=' .env "$SECRETS_ENV_FILE"; then
    problems="$problems KEYCLOAK_DB_PASSWORD"
  fi
  [ -z "$problems" ] || fail "a bundled service is switched off in COMPOSE_PROFILES but its replacement is not set:$problems - nothing was changed (docs/external-services.md)"
}

# Prometheus scrapes RabbitMQ at the address in targets/rabbitmq.yml. With the
# bundled broker switched off there is nothing to scrape, and an empty list keeps
# the "RabbitMQ is down" alert from firing for a container that does not exist.
# Rewritten on every deploy so it always matches COMPOSE_PROFILES.
write_monitoring_targets() {
  local dir=../monitoring/prometheus/targets file new
  file="$dir/rabbitmq.yml"
  if [[ ",$(active_profiles)," == *",rabbitmq,"* ]]; then
    new='- targets: ["rabbitmq:15692"]
  labels:
    service: rabbitmq'
  else
    new='[]'
  fi
  new="# Written by deploy.sh from COMPOSE_PROFILES - do not edit.
$new"
  mkdir -p "$dir"
  if [ "$(cat "$file" 2>/dev/null)" != "$new" ]; then
    printf '%s
' "$new" > "$file"
    log "monitoring: updated $file"
  fi
}

deploy() {
  local new="$1" prev
  [[ "$new" =~ ^[A-Za-z0-9._-]+$ ]] || fail "invalid image tag: $new"
  prev="$(env_value IMAGE_TAG)"
  [[ "$prev" == replace-with-* ]] && prev=""

  local missing; missing="$(missing_secrets)"
  [ -z "$missing" ] || fail "secrets not available: $(echo "$missing" | tr '\n' ' ')- render them from OpenBao first (bash ../openbao/bao-ops.sh render) or put them in .env. Nothing was changed."

  check_external_services
  write_monitoring_targets
  log "deploying $new (currently: ${prev:-none})"
  if [ "${SKIP_PULL:-0}" = "1" ]; then
    log "SKIP_PULL=1 - using the images already on this host"
  else
    IMAGE_TAG="$new" dc pull --quiet "${APP_SERVICES[@]}" "${OPTIONAL_SERVICES[@]}" \
      || fail "could not pull images for $new - nothing was changed"
  fi

  # bring_up inside the `if` so a failing `compose up` (e.g. keycloak-bootstrap
  # exiting non-zero) still reaches diagnostics and rollback under `set -e`.
  if bring_up "$new" && wait_for_health && smoke_test; then
    set_image_tag "$new"
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$new" >> "$HISTORY_FILE"
    check_nginx_drift
    log "SUCCESS: $new is live"
    report_optional
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
    # Read-only, so without rendered secrets it's safe to satisfy the
    # compose file's required variables with throwaway placeholders.
    missing="$(missing_secrets)"
    if [ -n "$missing" ]; then
      placeholder_file="$(mktemp)"; trap 'rm -f "$placeholder_file"' EXIT
      [ -f "$SECRETS_ENV_FILE" ] && cat "$SECRETS_ENV_FILE" >"$placeholder_file"
      while read -r k; do printf "%s='unavailable'\n" "$k"; done <<<"$missing" >>"$placeholder_file"
      SECRETS_ENV_FILE="$placeholder_file"
    fi
    log "deployed tag: $(env_value IMAGE_TAG)"
    [ -f "$HISTORY_FILE" ] && { log "recent deploys:"; tail -n 10 "$HISTORY_FILE"; }
    for svc in "${APP_SERVICES[@]}" "${OPTIONAL_SERVICES[@]}"; do log "$svc: $(container_state "$svc")"; done
    dc ps
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
