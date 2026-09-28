#!/usr/bin/env bash
# Phase 2 + evaluation: structures the stored OCR outputs (results/*.ocr.json
# from run_e2e.py) with the compose `llm` service, sampling its memory, then
# scores them with evaluate_quality.py.
#
# usage (repo root, Git Bash; `llm` + `llm-model-init` already up):
#   LLM_MODEL=qwen2.5:7b-instruct \
#     scripts/dev/structuring_e2e/quality/run_quality.sh <report-name> <stem>...
set -euo pipefail
export MSYS_NO_PATHCONV=1
E=scripts/dev/structuring_e2e
W=backend/workers/ocr-worker
CONTAINER=${LLM_CONTAINER:-oakdms-llm}
MODEL=${LLM_MODEL:-qwen2.5:7b-instruct}
REPORT=$1; shift
mem_log="$E/results/memory.$REPORT.log"

# Memory sampler: one `docker stats` reading every 3 s while structuring runs.
( while true; do docker stats --no-stream --format '{{.MemUsage}}' "$CONTAINER" >> "$mem_log" 2>/dev/null || true; sleep 3; done ) &
sampler=$!
trap 'kill $sampler 2>/dev/null || true' EXIT

# Same path as ocr-worker: HTTP to http://llm:11434 on the no-egress
# llm-internal network.
docker run --rm --network local_llm-internal -e PYTHONDONTWRITEBYTECODE=1 \
  -e LLM_BASE_URL=http://llm:11434 -e LLM_MODEL="$MODEL" -e LLM_GROUNDING_POLICY=flag \
  -e LLM_TIMEOUT_SECONDS="${LLM_TIMEOUT_SECONDS:-900}" \
  -v "$(pwd -W)/$W/src:/app/src:ro" -v "$(pwd -W)/$E:/e2e" \
  --entrypoint python local-ocr-worker:latest /e2e/structure_stored.py /e2e/results "$@"

kill $sampler 2>/dev/null || true
echo "peak llm container memory: $(sort -h "$mem_log" | tail -1)"
python "$E/quality/evaluate_quality.py" "$E/results" "$E/quality/$REPORT"
