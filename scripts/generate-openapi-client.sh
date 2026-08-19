#!/usr/bin/env bash
# Generates a TypeScript API client for frontend/packages/api-client from
# dms-core's live OpenAPI spec. Requires the local stack to be running
# (see setup-local-env.sh) and @openapitools/openapi-generator-cli
# available via npx.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DMS_CORE_URL="${DMS_CORE_URL:-http://localhost:8080}"
OUTPUT_DIR="$REPO_ROOT/frontend/packages/api-client/src/generated"

echo "==> Fetching OpenAPI spec from $DMS_CORE_URL/v3/api-docs"
SPEC_FILE="$(mktemp)"
curl -fsS "$DMS_CORE_URL/v3/api-docs" -o "$SPEC_FILE"

echo "==> Generating TypeScript client into $OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
npx --yes @openapitools/openapi-generator-cli generate \
    -i "$SPEC_FILE" \
    -g typescript-fetch \
    -o "$OUTPUT_DIR" \
    --additional-properties=supportsES6=true

rm -f "$SPEC_FILE"
echo "==> Done. Generated client at $OUTPUT_DIR"
