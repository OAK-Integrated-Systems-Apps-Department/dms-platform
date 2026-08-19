#!/usr/bin/env bash
# Brings up the full local dev stack: Postgres, RabbitMQ, Keycloak (realm
# auto-imported), a one-shot Keycloak bootstrap job (grants dms-core-admin
# the manage-users permission - see infra/keycloak/README.md for why this
# can't be expressed in the realm import itself), and dms-core. No manual
# Keycloak configuration is required.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> Decrypting secrets from OpenBao (infra/secrets/dms-core.secrets.enc.env)"
echo "    Requires BAO_ADDR and BAO_TOKEN to already be exported in this shell."
"$REPO_ROOT/scripts/openbao-decrypt-secrets.sh"

echo "==> Starting local stack (infra/local/docker-compose.yml)"
cd "$REPO_ROOT/infra/local"
docker compose up --build -d

# Containers have already read the env vars they need at this point, so the
# plaintext file no longer needs to sit on disk.
rm -f "$REPO_ROOT/infra/secrets/dms-core.secrets.env"
echo "==> Shredded plaintext infra/secrets/dms-core.secrets.env (containers already have what they need)"

echo ""
echo "==> Waiting for dms-core to report healthy (this includes waiting for"
echo "    the keycloak-bootstrap job to finish first)..."
for _ in $(seq 1 90); do
    if curl -fs http://localhost:8080/actuator/health >/dev/null 2>&1; then
        echo "    dms-core is up."
        break
    fi
    sleep 2
done

cat <<'MSG'

==> Stack is up, fully configured, no manual steps required:
      dms-core API           http://localhost:8080
      Swagger UI              http://localhost:8080/swagger-ui.html
      Keycloak admin console   http://localhost:8081  (admin / admin)
      RabbitMQ management      http://localhost:15672  (dms / changeme)

If dms-core didn't come up, check the bootstrap job's logs first:
      docker compose -f infra/local/docker-compose.yml logs keycloak-bootstrap
MSG
