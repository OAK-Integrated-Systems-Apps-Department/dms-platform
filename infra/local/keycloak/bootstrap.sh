#!/bin/sh
set -e

# Wait for Keycloak to be ready
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

# Get admin token
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

echo "Token obtained. Granting manage-users role to $KEYCLOAK_ADMIN_CLIENT_ID..."

# Get the service account user ID
USER_RESPONSE=$(curl -s -X GET "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/users?username=service-account-$KEYCLOAK_ADMIN_CLIENT_ID&exact=true" \
  -H "Authorization: Bearer $TOKEN")

USER_ID=$(echo "$USER_RESPONSE" | grep -o '"id":"[^"]*' | head -1 | cut -d'"' -f4)

if [ -z "$USER_ID" ]; then
  echo "Failed to find service account user"
  echo "Response: $USER_RESPONSE"
  exit 1
fi

# Get the manage-users role ID from the realm
ROLE_RESPONSE=$(curl -s -X GET "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/roles?search=manage-users&max=1" \
  -H "Authorization: Bearer $TOKEN")

ROLE_ID=$(echo "$ROLE_RESPONSE" | grep -o '"id":"[^"]*' | head -1 | cut -d'"' -f4)

if [ -z "$ROLE_ID" ]; then
  echo "Failed to find manage-users role"
  echo "Response: $ROLE_RESPONSE"
  exit 1
fi

echo "User ID: $USER_ID, Role ID: $ROLE_ID"

# Assign the role to the service account
curl -s -X POST "http://keycloak:8080/admin/realms/$KEYCLOAK_REALM/users/$USER_ID/role-mappings/realm" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "[{\"id\":\"$ROLE_ID\",\"name\":\"manage-users\"}]"

echo "Bootstrap completed successfully"
exit 0
