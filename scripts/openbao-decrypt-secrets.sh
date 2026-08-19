#!/usr/bin/env bash
#
# Decrypts infra/secrets/dms-core.secrets.enc.env (OpenBao Transit ciphertext)
# back into a plaintext .env file for local `docker compose up`, using the
# transit/decrypt endpoint against the same OpenBao instance/key that
# encrypted it.
#
# Requires: bao CLI on PATH, BAO_ADDR and BAO_TOKEN exported.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRETS_DIR="$REPO_ROOT/infra/secrets"
ENCRYPTED_FILE="${1:-$SECRETS_DIR/dms-core.secrets.enc.env}"
PLAINTEXT_FILE="${2:-$SECRETS_DIR/dms-core.secrets.env}"
TRANSIT_KEY="${OPENBAO_TRANSIT_KEY:-dms-core-secrets}"

command -v bao >/dev/null 2>&1 || { echo "ERROR: 'bao' CLI not found on PATH." >&2; exit 1; }
: "${BAO_ADDR:?Set BAO_ADDR, e.g. export BAO_ADDR=http://127.0.0.1:8200}"
: "${BAO_TOKEN:?Set BAO_TOKEN to a token with decrypt access on transit/$TRANSIT_KEY}"

[ -f "$ENCRYPTED_FILE" ] || { echo "ERROR: $ENCRYPTED_FILE not found." >&2; exit 1; }

echo "==> Decrypting $ENCRYPTED_FILE -> $PLAINTEXT_FILE (transit key: $TRANSIT_KEY)"
umask 177
> "$PLAINTEXT_FILE"

while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    ''|'#'*) continue ;;
  esac
  key="${line%%=*}"
  ciphertext="${line#*=}"
  plaintext_b64=$(bao write -field=plaintext "transit/decrypt/${TRANSIT_KEY}" ciphertext="$ciphertext")
  value=$(printf '%s' "$plaintext_b64" | base64 -d)
  echo "${key}=${value}" >> "$PLAINTEXT_FILE"
  echo "    decrypted ${key}"
done < "$ENCRYPTED_FILE"

chmod 600 "$PLAINTEXT_FILE"
echo "==> Done. $PLAINTEXT_FILE is plaintext (chmod 600) - load it and delete when finished, never commit it."
