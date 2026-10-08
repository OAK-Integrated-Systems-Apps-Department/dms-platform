#!/usr/bin/env bash
# Starts the throwaway services the *IT tests need beyond Postgres/Redis/
# RabbitMQ: an OpenBao dev server (Transit + an AppRole) and a Keycloak with
# the oak-dms realm imported and bootstrapped. Used by ci.yml's integration
# job; runs locally too (pick free ports, e.g. KEYCLOAK_PORT=18081).
#
# Writes the settings the tests read as KEY=value lines to $IT_ENV_FILE
# (default: $GITHUB_ENV in Actions). Every value is CI-only and generated
# per run - nothing here is a real secret.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# Git Bash on Windows: docker needs C:/... paths (run with MSYS_NO_PATHCONV=1).
command -v cygpath >/dev/null && ROOT="$(cygpath -m "$ROOT")"
OPENBAO_PORT="${OPENBAO_PORT:-18200}"
KEYCLOAK_PORT="${KEYCLOAK_PORT:-8081}"
NETWORK="${IT_NETWORK:-dms-it}"
OUT="${IT_ENV_FILE:-${GITHUB_ENV:?set IT_ENV_FILE or run in GitHub Actions}}"
PREFIX="${IT_CONTAINER_PREFIX:-dms-it}"

random() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
emit() { # KEY value - masked in the Actions log, appended to the env file
  [ -n "${GITHUB_ACTIONS:-}" ] && echo "::add-mask::$2"
  printf '%s=%s\n' "$1" "$2" >> "$OUT"
}
wait_for() { # url, what
  for _ in $(seq 1 90); do
    curl -sf "$1" >/dev/null && return 0
    sleep 2
  done
  echo "$2 did not come up at $1" >&2
  return 1
}

docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK" >/dev/null

# ---------------------------------------------------------------- OpenBao
BAO_TOKEN="$(random)"
docker run -d --name "$PREFIX-openbao" --network "$NETWORK" -p "$OPENBAO_PORT:8200" \
  -e BAO_DEV_ROOT_TOKEN_ID="$BAO_TOKEN" openbao/openbao:2.6.3 server -dev >/dev/null
wait_for "http://localhost:$OPENBAO_PORT/v1/sys/health" OpenBao
bao() { docker exec -i -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN="$BAO_TOKEN" "$PREFIX-openbao" bao "$@"; }
bao secrets enable transit >/dev/null
bao auth enable approle >/dev/null
bao policy write dms-it - >/dev/null <<'HCL'
path "transit/keys/dms-it*"            { capabilities = ["read", "create", "update"] }
path "transit/keys/dms-it*/rotate"     { capabilities = ["update"] }
path "transit/datakey/plaintext/dms-it*" { capabilities = ["update"] }
path "transit/encrypt/dms-it*"         { capabilities = ["update"] }
path "transit/decrypt/dms-it*"         { capabilities = ["update"] }
path "transit/rewrap/dms-it*"          { capabilities = ["update"] }
HCL
bao write auth/approle/role/dms-it token_policies=dms-it token_ttl=1h >/dev/null
emit OPENBAO_IT_ADDR "http://localhost:$OPENBAO_PORT"
emit OPENBAO_IT_TOKEN "$BAO_TOKEN"
emit OPENBAO_IT_ROLE_ID "$(bao read -field=role_id auth/approle/role/dms-it/role-id)"
emit OPENBAO_IT_SECRET_ID "$(bao write -f -field=secret_id auth/approle/role/dms-it/secret-id)"

# ---------------------------------------------------------------- Keycloak
KC_ADMIN_PASSWORD="$(random)"
CLIENT_SECRET="$(random)"
# The bootstrap script calls Keycloak as http://keycloak:8080, so it gets that
# alias on the private network. start-dev keeps Keycloak's own data in memory.
docker run -d --name "$PREFIX-keycloak" --network "$NETWORK" --network-alias keycloak \
  -p "$KEYCLOAK_PORT:8080" \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD="$KC_ADMIN_PASSWORD" \
  -e KEYCLOAK_ADMIN_CLIENT_SECRET="$CLIENT_SECRET" \
  -e OAK_DMS_WEB_URL=http://localhost:3000 \
  -v "$ROOT/infra/local/keycloak/oak-dms-realm.json:/opt/keycloak/data/import/oak-dms-realm.json:ro" \
  quay.io/keycloak/keycloak:26.7.0 start-dev --import-realm >/dev/null
wait_for "http://localhost:$KEYCLOAK_PORT/realms/oak-dms" Keycloak
docker run --rm --network "$NETWORK" \
  -e KEYCLOAK_BOOTSTRAP_ADMIN_USER=admin -e KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD="$KC_ADMIN_PASSWORD" \
  -e KEYCLOAK_REALM=oak-dms -e KEYCLOAK_ADMIN_CLIENT_ID=dms-core-admin \
  -e KEYCLOAK_WEB_CLIENT_ID=oak-dms-web -e KEYCLOAK_WEB_CLIENT_URL=http://localhost:3000 \
  -e KEYCLOAK_SMTP_HOST=localhost -e KEYCLOAK_SMTP_PORT=1025 -e KEYCLOAK_SMTP_FROM=no-reply@ci.invalid \
  -e KEYCLOAK_SMTP_FROM_DISPLAY_NAME="Oak DMS CI" -e KEYCLOAK_SMTP_AUTH=false \
  -e KEYCLOAK_SMTP_SSL=false -e KEYCLOAK_SMTP_STARTTLS=false \
  -v "$ROOT/infra/keycloak/bootstrap.sh:/bootstrap/bootstrap.sh:ro" \
  alpine:3 /bin/sh /bootstrap/bootstrap.sh >/dev/null
emit KEYCLOAK_SERVER_URL "http://localhost:$KEYCLOAK_PORT"
emit KEYCLOAK_ISSUER_URI "http://localhost:$KEYCLOAK_PORT/realms/oak-dms"
emit KEYCLOAK_JWK_SET_URI "http://localhost:$KEYCLOAK_PORT/realms/oak-dms/protocol/openid-connect/certs"
emit KEYCLOAK_ADMIN_CLIENT_SECRET "$CLIENT_SECRET"
echo "OpenBao on :$OPENBAO_PORT and Keycloak on :$KEYCLOAK_PORT are ready"
