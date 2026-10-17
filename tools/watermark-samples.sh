#!/usr/bin/env bash
#
# Produces watermarked samples of every format, placement and page scope and
# renders them to PNGs you can look at.
#
# This is NOT part of CI, on purpose. Every watermark bug that mattered -
# blank tiling, unjoined Arabic, Hebrew running the wrong way, a font that
# resolved differently under load - passed every assertion and was obvious
# within two seconds of looking at a page. Gating CI on image comparison
# would instead mean a golden-image suite that breaks on every font package
# update, so the evidence is produced on demand and a person judges it.
#
# Usage:   tools/watermark-samples.sh [output-dir]
# Default output dir: ../dms-watermark-samples, i.e. OUTSIDE the repository,
# so samples are never accidentally committed.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$(cd "$REPO_ROOT/.." && pwd)/dms-watermark-samples}"
# The project's own gotenberg container is internal-only (no published
# ports), so a throwaway one is started here rather than changing the
# compose file for a developer tool.
GOTENBERG_PORT="${WATERMARK_SAMPLES_PORT:-3099}"
GOTENBERG_NAME="watermark-samples-gotenberg"

cleanup() {
  if [ -n "${STARTED_GOTENBERG:-}" ]; then
    echo "Stopping $GOTENBERG_NAME"
    docker rm -f "$GOTENBERG_NAME" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if curl -fsS --max-time 2 "http://localhost:${GOTENBERG_PORT}/health" >/dev/null 2>&1; then
  echo "Using the Gotenberg already listening on ${GOTENBERG_PORT}"
else
  echo "Starting a throwaway Gotenberg on ${GOTENBERG_PORT}"
  docker rm -f "$GOTENBERG_NAME" >/dev/null 2>&1 || true
  docker run -d --rm --name "$GOTENBERG_NAME" \
    -p "${GOTENBERG_PORT}:3000" \
    gotenberg/gotenberg:8 \
    gotenberg --chromium-disable-routes=true --libreoffice-auto-start=true --log-level=warn \
    >/dev/null
  STARTED_GOTENBERG=1

  echo -n "Waiting for Gotenberg"
  for _ in $(seq 1 60); do
    if curl -fsS --max-time 2 "http://localhost:${GOTENBERG_PORT}/health" >/dev/null 2>&1; then
      echo " - ready"
      break
    fi
    echo -n "."
    sleep 1
  done
fi

mkdir -p "$OUT_DIR"
echo "Writing samples to $OUT_DIR"

cd "$REPO_ROOT/backend/dms-core"
WATERMARK_SAMPLES_DIR="$OUT_DIR" \
WATERMARK_SAMPLES_GOTENBERG="http://localhost:${GOTENBERG_PORT}" \
  ./mvnw -q -o surefire:test \
    -Dtest=WatermarkSampleGenerator \
    -DfailIfNoSpecifiedTests=false

echo
echo "Done. Open: $OUT_DIR/pages"
echo "Read:  $OUT_DIR/README.txt for what to look for."
