#!/usr/bin/env bash
# One-shot automated verification of OCR + Qwen structured extraction.
#
#   scripts/dev/structuring_e2e/verify_all.sh            # repo root, Git Bash
#
# Needs the local stack up (postgres, minio, ocr-worker, llm) with the
# models listed in MODELS pulled into `llm`. Every step records PASS/FAIL in
# results/steps.tsv; final_report.py turns everything into
# quality/final_report.local.md. The browser portion is not scripted here:
# see README "Browser verification".
set -uo pipefail
export MSYS_NO_PATHCONV=1 PYTHONDONTWRITEBYTECODE=1
cd "$(git rev-parse --show-toplevel)"
E=scripts/dev/structuring_e2e; W=backend/workers/ocr-worker; R=$E/results
MODELS=${MODELS:-"qwen2.5:3b-instruct qwen2.5:7b-instruct"}
TEST_IMAGE=${TEST_IMAGE:-ocr-worker-test:local}
DOCS="employee_contract_native employee_contract_scan invoice_scanned bank_statement_3pages_native vendor_form_native hr_difficult_native hr_difficult_scanned real_purchase_order"
mkdir -p "$R"; : > "$R/steps.tsv"
step() { local name=$1; shift; local t0=$SECONDS; if "$@" > "$R/step.$name.log" 2>&1; then r=PASS; else r=FAIL; fi
         printf '%s\t%s\t%ss\n' "$name" "$r" "$((SECONDS - t0))" | tee -a "$R/steps.tsv"; }
worker_env() { for v in DB_URL MINIO_ENDPOINT MINIO_ACCESS_KEY MINIO_SECRET_KEY MINIO_SOURCE_BUCKET INTERNAL_SERVICE_TOKEN; do
               val=$(docker exec oakdms-ocr-worker printenv "$v" 2>/dev/null) && printf -- '-e %s=%s ' "$v" "$val"; done; }
run_worker_image() { docker run --rm -e PYTHONDONTWRITEBYTECODE=1 -v "$(pwd -W)/$W/src:/app/src:ro" -v "$(pwd -W)/$E:/e2e" -v "$(pwd -W)/docs:/realdocs:ro" "$@"; }

# 1-2. automated test suites
step ocr_worker_tests docker run --rm -e PYTHONDONTWRITEBYTECODE=1 -v "$(pwd -W)/$W/src:/app/src" -v "$(pwd -W)/$W/tests:/app/tests" \
     -v "$(pwd -W)/$W/pyproject.toml:/app/pyproject.toml" -w /app --entrypoint python "$TEST_IMAGE" -m pytest -q -p no:cacheprovider
step dms_core_tests bash -c "cd backend/dms-core && JAVA_HOME='${JAVA_HOME_17:-C:\\Program Files\\Java\\jdk-17}' ./mvnw -o -q test -Dtest='com.oak.dms.document.*Test,com.oak.dms.ocrworker.*Test' -Dsurefire.failIfNoSpecifiedTests=false"

# 3. documents + real PaddleOCR/Docling on every one (and the negative cases)
step make_docs docker run --rm --user root -v "$(pwd -W)/$E:/e2e" --entrypoint python local-ocr-worker:latest /e2e/make_docs.py /e2e/docs
# The real DMS document: a local copy under the gitignored docs/ folder.
[ -f "docs/4400051211 (2) (2) (1) (1).PDF" ] && cp "docs/4400051211 (2) (2) (1) (1).PDF" "$E/docs/real_purchase_order.pdf"
step paddleocr_docling run_worker_image -e E2E_NO_LLM=1 -v local_ocr_worker_models:/home/oakdms/.paddlex -v local_ocr_worker_hf_cache:/home/oakdms/.cache/huggingface \
     --entrypoint sh local-ocr-worker:latest -c "python /e2e/run_e2e.py /e2e/results /e2e/docs/*.pdf /e2e/docs/*.png && python /e2e/quality/check_ocr.py /e2e/results"

# 4. connectivity: worker -> llm over the internal network; llm has no egress
step llm_connectivity bash -c "docker exec oakdms-ocr-worker python -c \"import urllib.request,json,os; r=urllib.request.Request('http://localhost:8090/ocr/structured/health',headers={'X-Internal-Token':os.environ['INTERNAL_SERVICE_TOKEN']}); h=json.load(urllib.request.urlopen(r)); print(h); assert h['reachable'] and h['model_available'], h\" \
     && ! docker exec oakdms-llm sh -c 'timeout 15 ollama pull tinyllama' && echo 'llm has no internet route (pull failed as intended)'"

# 5. Pipeline A (existing extraction) on the stored OCR output
step pipeline_a run_worker_image --entrypoint python local-ocr-worker:latest /e2e/quality/ab_pipeline_a.py /e2e/results $DOCS
python $E/quality/evaluate_quality.py $R $E/quality/report.local.pipelineA --suffix pipelineA > "$R/eval.pipelineA.log" 2>&1

# 6. Pipeline B (Qwen, targeted) per available model, memory sampled
AVAILABLE=$(docker exec oakdms-llm ollama list | awk 'NR>1{print $1}')
for M in $MODELS; do
  tag=qwen_$(echo "$M" | tr -c 'a-zA-Z0-9\n' '_')
  if ! grep -qx "$M" <<<"$AVAILABLE"; then printf '%s\tSKIP\tmodel not pulled\n' "structuring_$tag" | tee -a "$R/steps.tsv"; continue; fi
  ( while true; do docker stats --no-stream --format '{{.MemUsage}}' oakdms-llm >> "$R/memory.$tag.log" 2>/dev/null; sleep 3; done ) & sampler=$!
  step "structuring_$tag" docker run --rm --network local_llm-internal -e PYTHONDONTWRITEBYTECODE=1 -e LLM_BASE_URL=http://llm:11434 -e LLM_MODEL="$M" \
       -e LLM_GROUNDING_POLICY=flag -e LLM_TIMEOUT_SECONDS=1800 -v "$(pwd -W)/$W/src:/app/src:ro" -v "$(pwd -W)/$E:/e2e" \
       --entrypoint python local-ocr-worker:latest /e2e/structure_stored.py /e2e/results --targets --tag "$tag" $DOCS
  kill $sampler 2>/dev/null
  python $E/quality/evaluate_quality.py $R "$E/quality/report.local.$tag" --suffix "$tag" > "$R/eval.$tag.log" 2>&1
done

# 7. Live end-to-end through the running stack (model = whatever ocr-worker is configured with)
LIVE_MODEL=$(docker exec oakdms-ocr-worker printenv LLM_MODEL)
( while true; do docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' oakdms-llm oakdms-ocr-worker >> "$R/memory.live.log" 2>/dev/null; sleep 3; done ) & sampler=$!
step live_e2e run_worker_image --network local_default $(worker_env) --entrypoint python local-ocr-worker:latest /e2e/live_e2e.py /e2e/results /e2e/docs $DOCS
kill $sampler 2>/dev/null
for s in liveA liveB; do python $E/quality/evaluate_quality.py $R "$E/quality/report.local.$s" --suffix $s > "$R/eval.$s.log" 2>&1; done

# 8. Final report
python $E/quality/final_report.py "$R" "$E/quality" "$LIVE_MODEL"
echo "report: $E/quality/final_report.local.md"
