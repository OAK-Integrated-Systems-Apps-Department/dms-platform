#!/bin/sh
apk add curl > /dev/null 2>&1
TOKEN=$(curl -s -X POST http://keycloak:8080/realms/master/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=admin-cli" \
  -d "username=admin" \
  -d "password=admin" \
  -d "grant_type=password" | grep -o '"access_token":"[^"]*' | cut -d'"' -f4)
echo "Token: $TOKEN"
curl -s -H "Authorization: Bearer $TOKEN" http://keycloak:8080/admin/realms/oak-dms/roles
