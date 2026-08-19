#!/usr/bin/env bash
#
# Closes the one gap a Keycloak realm export genuinely can't express:
# granting a client's service account a role on ANOTHER client
# (realm-management's manage-users), which requires that other client to
# already exist with a resolved internal id - a realm JSON import creates
# everything in one pass and has no way to reference an id that doesn't
# exist yet.
#
# Safe to re-run: checks current state before making any change, so this
# can run every time the stack starts without erroring on a role that's
# already granted.
#
# Deliberately uses ONLY kcadm.sh and bash builtins - no curl. The
# official quay.io/keycloak/keycloak image does not reliably ship curl
# (see github.com/keycloak/keycloak/issues/17438; there's no package
# manager in the runtime image to install it with either), so a
# curl-based readiness check would fail. kcadm.sh itself makes its HTTP
# calls via the JVM, not curl, so it has no such dependency - this script
# uses repeated kcadm.sh calls as its own readiness probe instead of
# polling an HTTP endpoint separately.
#
# Works against ANY Keycloak instance reachable at KEYCLOAK_URL - local
# Docker, a shared dev environment, or a real deployment - not just the
# infra/local docker-compose stack.
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
BOOTSTRAP_ADMIN_USER="${KEYCLOAK_BOOTSTRAP_ADMIN_USER:-admin}"
BOOTSTRAP_ADMIN_PASSWORD="${KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD:-admin}"
REALM="${KEYCLOAK_REALM:-oak-dms}"
ADMIN_CLIENT_ID="${KEYCLOAK_ADMIN_CLIENT_ID:-dms-core-admin}"
SERVICE_ACCOUNT_ROLE="${KEYCLOAK_SERVICE_ACCOUNT_ROLE:-manage-users}"

KCADM="${KCADM_BIN:-/opt/keycloak/bin/kcadm.sh}"
SERVICE_ACCOUNT_USERNAME="service-account-${ADMIN_CLIENT_ID}"

echo "==> Waiting for Keycloak at ${KEYCLOAK_URL} to accept admin logins..."
attempt=0
until "$KCADM" config credentials \
        --server "$KEYCLOAK_URL" \
        --realm master \
        --user "$BOOTSTRAP_ADMIN_USER" \
        --password "$BOOTSTRAP_ADMIN_PASSWORD" 2>/dev/null
do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then
        echo "==> ERROR: Keycloak did not become ready after $attempt attempts." >&2
        exit 1
    fi
    sleep 2
done
echo "==> Authenticated."

echo "==> Waiting for realm '${REALM}' to exist (created by --import-realm on first boot)"
attempt=0
until "$KCADM" get "realms/${REALM}" >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
        echo "==> ERROR: realm '${REALM}' never appeared. Check the realm import succeeded." >&2
        exit 1
    fi
    sleep 2
done

echo "==> Checking whether ${SERVICE_ACCOUNT_USERNAME} already has ${SERVICE_ACCOUNT_ROLE}"
if "$KCADM" get-roles \
        -r "$REALM" \
        --uusername "$SERVICE_ACCOUNT_USERNAME" \
        --cclientid realm-management \
    | grep -q "\"name\" *: *\"${SERVICE_ACCOUNT_ROLE}\""
then
    echo "==> Already granted - nothing to do."
    exit 0
fi

echo "==> Granting ${SERVICE_ACCOUNT_ROLE} (client role on realm-management) to ${SERVICE_ACCOUNT_USERNAME}"
# The pre-check above should already have caught an existing grant; this
# add-roles call is also tolerant of that case in case of a false-negative
# pre-check (e.g. unexpected kcadm output formatting), since Keycloak
# treats re-adding an already-held role as a no-op rather than a hard error
# in most versions - but we don't want a genuinely new failure (bad
# credentials, unreachable realm-management client, etc.) to be silently
# swallowed, so only ignore failure AFTER re-confirming the role is present.
if ! "$KCADM" add-roles \
        -r "$REALM" \
        --uusername "$SERVICE_ACCOUNT_USERNAME" \
        --cclientid realm-management \
        --rolename "$SERVICE_ACCOUNT_ROLE"
then
    if "$KCADM" get-roles -r "$REALM" --uusername "$SERVICE_ACCOUNT_USERNAME" --cclientid realm-management \
        | grep -q "\"name\" *: *\"${SERVICE_ACCOUNT_ROLE}\""
    then
        echo "==> add-roles reported an error but the role is present - treating as already-granted."
    else
        echo "==> ERROR: failed to grant ${SERVICE_ACCOUNT_ROLE} and it is still not present. See kcadm output above." >&2
        exit 1
    fi
fi

echo "==> Done. ${ADMIN_CLIENT_ID}'s service account can now call the Keycloak Admin REST API."
