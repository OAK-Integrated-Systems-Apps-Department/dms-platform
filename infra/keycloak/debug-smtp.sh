#!/bin/sh
set -e

if ! command -v curl > /dev/null; then
  apk add --no-cache curl > /dev/null
fi
if ! command -v jq > /dev/null; then
  apk add --no-cache jq > /dev/null
fi

echo "Obtaining admin token..."
TOKEN=$(curl -s -X POST "http://keycloak:8080/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" \
  -d "username=$KEYCLOAK_BOOTSTRAP_ADMIN_USER" \
  -d "password=$KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD" \
  -d "grant_type=password" | jq -r '.access_token')

if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
  echo "Failed to obtain admin token"
  exit 1
fi

echo ""
echo "=== Current smtpServer config on realm '$KEYCLOAK_REALM' ==="
curl -s "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM" \
  -H "Authorization: Bearer $TOKEN" | jq '.smtpServer'

if [ -n "$TARGET_USER_ID" ]; then
  echo ""
  echo "=== Sending execute-actions email to user $TARGET_USER_ID ==="
  HTTP_CODE=$(curl -s -o /tmp/resp.txt -w "%{http_code}" \
    -X PUT "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/users/$TARGET_USER_ID/execute-actions-email" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '["VERIFY_EMAIL","UPDATE_PASSWORD"]')
  echo "HTTP status: $HTTP_CODE"
  echo "Response body:"
  cat /tmp/resp.txt
  echo ""

  if [ "$HTTP_CODE" = "404" ]; then
    echo ""
    echo "=== That user ID doesn't exist. Listing up to 5 real users instead ==="
    curl -s "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/users?max=5" \
      -H "Authorization: Bearer $TOKEN" | jq '[.[] | {id, username, email}]'
  fi
else
  echo ""
  echo "(Set TARGET_USER_ID env var to also test-send an email to a specific user)"
fi