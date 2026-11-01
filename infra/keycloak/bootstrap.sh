#!/bin/sh
set -e

if ! command -v curl > /dev/null; then
  echo "Installing curl..."
  apk add --no-cache curl
fi

if ! command -v jq > /dev/null; then
  echo "Installing jq..."
  apk add --no-cache jq
fi

echo "Waiting for Keycloak to be ready..."
attempt=0
max_attempts=60
while [ $attempt -lt $max_attempts ]; do
  if curl -s -f http://keycloak:8080/realms/master > /dev/null 2>&1; then
    echo "Keycloak is ready"
    break
  fi
  attempt=$((attempt + 1))
  echo "Waiting... attempt $attempt/$max_attempts"
  sleep 2
done

echo "Obtaining admin token..."
RESPONSE=$(curl -s -X POST http://keycloak:8080/realms/master/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli" \
  -d "username=$KEYCLOAK_BOOTSTRAP_ADMIN_USER" \
  -d "password=$KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD" \
  -d "grant_type=password")

TOKEN=$(echo "$RESPONSE" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "Failed to obtain admin token"
  echo "Response: $RESPONSE"
  exit 1
fi

# dms-core's Admin REST API usage needs two realm-management client roles,
# not just one:
#   - manage-users: create/enable/disable/delete users, assign/remove
#     realm-role mappings on a user (KeycloakUserService#createUser,
#     assignRealmRole, removeRealmRole, etc.)
#   - manage-realm: create/rename realm roles themselves
#     (KeycloakUserService#createRealmRole, renameRealmRole) - defining a
#     role is realm configuration, distinct from assigning an existing role
#     to a user, so manage-users alone does not cover it.
# Granting only manage-users (as this script previously did) leaves role
# creation failing with 403 while user management appears to work fine,
# which is a confusing split to debug from the caller's side - dms-core
# only ever sees it as a generic 502 from the Keycloak Admin REST API.
REQUIRED_ROLES="manage-users manage-realm"

echo "Token obtained. Granting [$REQUIRED_ROLES] to $KEYCLOAK_ADMIN_CLIENT_ID..."

USER_RESPONSE=$(curl -s -X GET "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/users?username=service-account-$KEYCLOAK_ADMIN_CLIENT_ID&exact=true" \
  -H "Authorization: Bearer $TOKEN")

USER_ID=$(echo "$USER_RESPONSE" | grep -o '"id":"[^"]*' | head -1 | cut -d'"' -f4)

if [ -z "$USER_ID" ]; then
  echo "Failed to find service account user"
  echo "Response: $USER_RESPONSE"
  exit 1
fi

CLIENT_RESPONSE=$(curl -s -X GET "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/clients?clientId=realm-management&exact=true" \
  -H "Authorization: Bearer $TOKEN")

REALM_MGMT_CLIENT_UUID=$(echo "$CLIENT_RESPONSE" | grep -o '"id":"[^"]*' | head -1 | cut -d'"' -f4)

if [ -z "$REALM_MGMT_CLIENT_UUID" ]; then
  echo "Failed to find realm-management client"
  echo "Response: $CLIENT_RESPONSE"
  exit 1
fi

EXISTING_ROLES=$(curl -s -X GET "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/users/$USER_ID/role-mappings/clients/$REALM_MGMT_CLIENT_UUID" \
  -H "Authorization: Bearer $TOKEN")

TO_GRANT_JSON=""
for ROLE_NAME in $REQUIRED_ROLES; do
  if echo "$EXISTING_ROLES" | grep -q "\"name\":\"$ROLE_NAME\""; then
    echo "  $ROLE_NAME: already granted"
    continue
  fi

  ROLE_RESPONSE=$(curl -s -X GET "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/clients/$REALM_MGMT_CLIENT_UUID/roles/$ROLE_NAME" \
    -H "Authorization: Bearer $TOKEN")

  ROLE_ID=$(echo "$ROLE_RESPONSE" | grep -o '"id":"[^"]*' | head -1 | cut -d'"' -f4)

  if [ -z "$ROLE_ID" ]; then
    echo "Failed to find $ROLE_NAME role on realm-management client"
    echo "Response: $ROLE_RESPONSE"
    exit 1
  fi

  echo "  $ROLE_NAME: granting (role id $ROLE_ID)"
  if [ -z "$TO_GRANT_JSON" ]; then
    TO_GRANT_JSON="{\"id\":\"$ROLE_ID\",\"name\":\"$ROLE_NAME\"}"
  else
    TO_GRANT_JSON="$TO_GRANT_JSON,{\"id\":\"$ROLE_ID\",\"name\":\"$ROLE_NAME\"}"
  fi
done

if [ -z "$TO_GRANT_JSON" ]; then
  echo "Already granted - nothing to do."
else
  echo "User ID: $USER_ID, realm-management client UUID: $REALM_MGMT_CLIENT_UUID"

  curl -s -f -X POST "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/users/$USER_ID/role-mappings/clients/$REALM_MGMT_CLIENT_UUID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "[$TO_GRANT_JSON]"
fi

# --- SMTP config -------------------------------------------------------
# oak-dms-realm.json's smtpServer block only applies the FIRST time this
# realm is ever created (Keycloak's --import-realm uses IGNORE_EXISTING).
# On every subsequent start where the realm already exists, that block is
# never re-applied - so if it's edited later, or someone blanks the From
# field via the admin console's Email tab (its form doesn't always
# pre-fill on load), the realm silently keeps sending with no "from"
# address. Keycloak then rejects the send with "Invalid sender address
# 'null'" before it ever touches the network, which is easy to mistake
# for an SMTP connectivity problem - see KC-SERVICES0088 in the Keycloak
# logs. Re-applying this on every bootstrap run, unconditionally, means
# it can never drift from what's configured here regardless of import
# quirks or accidental console edits.
: "${KEYCLOAK_SMTP_HOST:?KEYCLOAK_SMTP_HOST is required}"
: "${KEYCLOAK_SMTP_PORT:?KEYCLOAK_SMTP_PORT is required}"
: "${KEYCLOAK_SMTP_FROM:?KEYCLOAK_SMTP_FROM is required}"
KEYCLOAK_SMTP_FROM_DISPLAY_NAME="${KEYCLOAK_SMTP_FROM_DISPLAY_NAME:-OAK iDocs}"
KEYCLOAK_SMTP_AUTH="${KEYCLOAK_SMTP_AUTH:-false}"
KEYCLOAK_SMTP_SSL="${KEYCLOAK_SMTP_SSL:-false}"
KEYCLOAK_SMTP_STARTTLS="${KEYCLOAK_SMTP_STARTTLS:-false}"

echo "Applying SMTP config to realm $KEYCLOAK_REALM (host=$KEYCLOAK_SMTP_HOST, from=$KEYCLOAK_SMTP_FROM)..."

SMTP_JSON=$(jq -n \
  --arg host "$KEYCLOAK_SMTP_HOST" \
  --arg port "$KEYCLOAK_SMTP_PORT" \
  --arg from "$KEYCLOAK_SMTP_FROM" \
  --arg fromDisplayName "$KEYCLOAK_SMTP_FROM_DISPLAY_NAME" \
  --arg auth "$KEYCLOAK_SMTP_AUTH" \
  --arg ssl "$KEYCLOAK_SMTP_SSL" \
  --arg starttls "$KEYCLOAK_SMTP_STARTTLS" \
  --arg user "${KEYCLOAK_SMTP_USER:-}" \
  --arg password "${KEYCLOAK_SMTP_PASSWORD:-}" \
  '{host: $host, port: $port, from: $from, fromDisplayName: $fromDisplayName,
    auth: $auth, ssl: $ssl, starttls: $starttls}
   + (if $auth == "true" then {user: $user, password: $password} else {} end)')

REALM_REPRESENTATION=$(curl -s -f -X GET "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM" \
  -H "Authorization: Bearer $TOKEN")

if [ -z "$REALM_REPRESENTATION" ]; then
  echo "Failed to fetch realm representation for $KEYCLOAK_REALM"
  exit 1
fi

UPDATED_REALM=$(echo "$REALM_REPRESENTATION" | jq --argjson smtp "$SMTP_JSON" '.smtpServer = $smtp')

curl -s -f -X PUT "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "$UPDATED_REALM"

echo "SMTP config applied"

# --- Web client URLs ---------------------------------------------------
# Same IGNORE_EXISTING problem as the SMTP block above: the realm file's
# ${OAK_DMS_WEB_URL} redirectUris/webOrigins are only substituted on the
# realm's FIRST import. Re-applying them here on every run means changing
# PUBLIC_HOST in infra/local/.env is enough to move an existing stack to a
# new host - no manual admin-console edits.
if [ -n "${KEYCLOAK_WEB_CLIENT_URL:-}" ]; then
  KEYCLOAK_WEB_CLIENT_ID="${KEYCLOAK_WEB_CLIENT_ID:-oak-dms-web}"
  WEB_CLIENT_URL="${KEYCLOAK_WEB_CLIENT_URL%/}"

  echo "Applying redirect URIs/web origins ($WEB_CLIENT_URL) to client $KEYCLOAK_WEB_CLIENT_ID..."

  WEB_CLIENT=$(curl -s -f -X GET "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/clients?clientId=$KEYCLOAK_WEB_CLIENT_ID&exact=true" \
    -H "Authorization: Bearer $TOKEN" | jq '.[0]')

  if [ -z "$WEB_CLIENT" ] || [ "$WEB_CLIENT" = "null" ]; then
    echo "Failed to find client $KEYCLOAK_WEB_CLIENT_ID"
    exit 1
  fi

  WEB_CLIENT_UUID=$(echo "$WEB_CLIENT" | jq -r '.id')
  UPDATED_WEB_CLIENT=$(echo "$WEB_CLIENT" | jq --arg url "$WEB_CLIENT_URL" \
    '.redirectUris = [$url + "/*"] | .webOrigins = [$url]')

  curl -s -f -X PUT "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/clients/$WEB_CLIENT_UUID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$UPDATED_WEB_CLIENT"

  echo "Web client URLs applied"
fi

# --- Platform superadmin password --------------------------------------
# oak-dms-realm.json creates superadmin@gmail.com with the PLATFORM_ADMIN
# role but DELIBERATELY no credential. It used to carry a committed password,
# which meant every deployment built from this repo - every client's server -
# shipped with the same known platform-admin login, and that password was in
# the git history for good.
#
# Set here instead, from the environment, and ONLY when the account has no
# credential yet. That distinction matters: this script re-runs on every
# `docker compose up`, so unconditionally setting the password would silently
# undo the administrator's own password change on the next restart.
#
# Without KEYCLOAK_SUPERADMIN_PASSWORD set there is simply no way in as
# superadmin - which is the right failure. It is loud, it happens at install
# time, and it cannot be mistaken for a working system.
if [ -n "${KEYCLOAK_SUPERADMIN_USERNAME:-}" ]; then
  SUPERADMIN_USER=$(curl -s -f -X GET \
    "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/users?username=$KEYCLOAK_SUPERADMIN_USERNAME&exact=true" \
    -H "Authorization: Bearer $TOKEN" | jq -r '.[0] // empty')

  if [ -z "$SUPERADMIN_USER" ]; then
    echo "Superadmin $KEYCLOAK_SUPERADMIN_USERNAME not found in realm $KEYCLOAK_REALM - skipping"
  else
    SUPERADMIN_ID=$(echo "$SUPERADMIN_USER" | jq -r '.id')
    EXISTING_CREDS=$(curl -s -f -X GET \
      "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/users/$SUPERADMIN_ID/credentials" \
      -H "Authorization: Bearer $TOKEN" | jq 'length')

    if [ "$EXISTING_CREDS" != "0" ]; then
      echo "Superadmin already has a password - leaving it alone"
    elif [ -z "${KEYCLOAK_SUPERADMIN_PASSWORD:-}" ]; then
      echo "WARNING: superadmin has no password and KEYCLOAK_SUPERADMIN_PASSWORD is unset."
      echo "         Nobody can sign in as the platform administrator."
      echo "         Set it in your .env and re-run this stack."
    else
      # temporary=true forces Keycloak's UPDATE_PASSWORD action at first
      # login, so whatever was typed into .env during install does not stay
      # as the permanent credential. Set KEYCLOAK_SUPERADMIN_PASSWORD_TEMPORARY
      # to false for a dev machine where that prompt is just friction.
      TEMPORARY="${KEYCLOAK_SUPERADMIN_PASSWORD_TEMPORARY:-true}"
      echo "Setting initial superadmin password (temporary=$TEMPORARY)..."
      curl -s -f -X PUT \
        "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/users/$SUPERADMIN_ID/reset-password" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "$(jq -n --arg p "$KEYCLOAK_SUPERADMIN_PASSWORD" --argjson t "$TEMPORARY" \
              '{type: "password", value: $p, temporary: $t}')"
      echo "Superadmin password set"
    fi
  fi
fi

echo "Bootstrap completed successfully"
exit 0