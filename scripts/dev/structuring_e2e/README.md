# Structured-extraction E2E (real OCR + real local LLM)

Runs ocr-worker's real `OcrPipeline` (upload validation, Docling,
PaddleOCR) over representative documents, then structures the stored OCR
output with a real local 7B model. See `backend/workers/ocr-worker/README.md`,
"Structured JSON extraction (local LLM)".

It runs in two phases because an 8GB Docker VM can't hold Docling +
PaddleOCR and a 7B model in one process. Phase 2 uses the same
`StructuredExtractionRunner.run` path as `POST /ocr/structured/{id}`.
SQLite and in-memory storage stand in for Postgres and MinIO. Nothing
leaves the machine.

All commands run from the repo root in Git Bash, with the `local` compose
stack up (for its model-cache volumes and network).

```bash
W=backend/workers/ocr-worker; E=scripts/dev/structuring_e2e; OUT=/tmp/structuring_e2e
export MSYS_NO_PATHCONV=1

# 0. Representative documents: native PDF, scanned PNG (150 DPI), image-only
#    invoice PDF with a ruled table, 3-page statement with a 75-row table,
#    blank page, truncated PDF, executable renamed to .pdf.
docker run --rm --user root -v "$(pwd -W)/$E:/e2e" --entrypoint python local-ocr-worker:latest /e2e/make_docs.py /e2e/docs

# 1. Real OCR -> $E/results/<name>.ocr.json (+ the exact model input, <name>.model_input.txt)
docker run --rm -e E2E_NO_LLM=1 -v "$(pwd -W)/$W/src:/app/src:ro" -v "$(pwd -W)/$E:/e2e" \
  -v local_ocr_worker_models:/home/oakdms/.paddlex -v local_ocr_worker_hf_cache:/home/oakdms/.cache/huggingface \
  --entrypoint python local-ocr-worker:latest /e2e/run_e2e.py /e2e/results /e2e/docs/*.pdf /e2e/docs/*.png
```

**2a. With the compose `llm` service** (Ollama; `llm-model-init` pulls the model). Phase 2 joins
`local_llm-internal`, the same no-egress network ocr-worker uses to reach `llm`:

```bash
LLM_MODEL=qwen2.5:7b-instruct docker compose -f infra/local/docker-compose.yml up -d llm llm-model-init   # CPU-only host: add LLM_IMAGE=alpine/ollama:0.34.2; NVIDIA: add -f infra/local/docker-compose.gpu.yml
docker run --rm --network local_llm-internal -e LLM_BASE_URL=http://llm:11434 -e LLM_MODEL=qwen2.5:7b-instruct \
  -v "$(pwd -W)/$W/src:/app/src:ro" -v "$(pwd -W)/$E:/e2e" --entrypoint python local-ocr-worker:latest \
  /e2e/structure_stored.py /e2e/results employee_contract_native employee_contract_scan invoice_scanned bank_statement_3pages_native blank_page
```

**2b. With llama.cpp** (smaller runtime image, ~0.3GB CPU build). Download
`Qwen2.5-7B-Instruct-Q4_K_M.gguf` from `bartowski/Qwen2.5-7B-Instruct-GGUF`
(sha256 `65b8fcd92af6b4fefa935c625d1ac27ea29dcb6ee14589c55a8f115ceaaa1423`)
into `$MODELS`, then:

```bash
docker run -d --name llm --network local_default -v "$MODELS:/models:ro" ghcr.io/ggml-org/llama.cpp:server \
  -m /models/Qwen2.5-7B-Instruct-Q4_K_M.gguf -c 8192 --parallel 1 --host 0.0.0.0 --port 8080
docker run --rm --network local_default -e LLM_PROVIDER=openai_compatible -e LLM_BASE_URL=http://llm:8080 \
  -e LLM_MODEL=qwen2.5-7b-instruct -v "$(pwd -W)/$W/src:/app/src:ro" -v "$(pwd -W)/$E:/e2e" \
  --entrypoint python local-ocr-worker:latest /e2e/structure_stored.py /e2e/results \
  employee_contract_native employee_contract_scan invoice_scanned bank_statement_3pages_native blank_page
docker rm -f llm
```

Results are written to `$E/results/<name>.structured.json` and
`structured_summary.json` (status, error code, field/table counts, token
usage, timings). `docs/`, `results/` and the model files are local test
artifacts, so don't commit them.
