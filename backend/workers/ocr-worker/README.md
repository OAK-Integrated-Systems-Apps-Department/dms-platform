# ocr-worker

Python microservice implementing Oak DMS's OCR & Scanning module
(requirement-registry.md section 2.4, OS-FR-001..006 / OS-NFR-001..006):
full-document OCR, zone OCR, rubber-band OCR, and structured field
extraction, stored as searchable metadata.

## Auth & service exposure -- internal-only

ocr-worker has **no login of its own** and is **never reachable from
outside the docker network** -- there is no published host port for it in
`infra/local/docker-compose.yml`. The access model is:

```
frontend -> dms-core (Keycloak-authenticated) -> ocr-worker (internal-only)
```

- ocr-worker does not validate JWTs or read cookies. It trusts
  `tenant_id`/`user_id`/`document_version_id` passed explicitly by the
  caller -- dms-core in production; for now, whatever calls the dev-trigger
  endpoint (`scripts/dev/smoke_test_ocr.py`, `scripts/dev/publish_fake_scan_event.py`).
- Every `/ocr/*` route additionally requires an `X-Internal-Token` header
  matching `INTERNAL_SERVICE_TOKEN` (see `infra/secrets/ocr-worker.secrets.env`).
  This is defense in depth, **not** an authentication scheme -- the real
  boundary is the missing published port. `/healthz`/`/readyz` don't
  require it (container-orchestration probes only, low risk).
- dms-core's passthrough `/ocr/*` routes and the real frontend upload/view
  page are **out of scope for this task** -- they depend on dms-core's
  `document`/`storage` modules, which don't exist yet. There is no
  standalone test UI either; verification here is via the dev-trigger
  endpoint and the smoke-test script only (see below), both of which have
  to run *on the docker network*, not from the bare host, precisely
  because there's no published port to reach from outside it.

## Native-text vs. PaddleOCR decision logic

Docling and PaddleOCR are not redundant with each other -- each page gets
routed to exactly one of them, decided at OCR time:

1. Load the document with Docling (`DocumentConverter`, its own OCR
   backends disabled -- see below).
2. **If the page has a real native text layer** (a normal, non-scanned PDF
   page), Docling extracts that text directly. That page's `method` in the
   output is `native_text`, confidence `1.0` (no OCR uncertainty), and
   PaddleOCR is **never called** for it.
3. **If the page has no text layer** (a scanned page, a photographed page,
   or an image-only PDF page -- including every plain PNG/JPEG/TIFF input,
   which never has a text layer at all), the page is rasterized via
   PyMuPDF and PaddleOCR runs on the resulting image. That page's `method`
   is `paddleocr`.
4. **Zone OCR and rubber-band OCR always run PaddleOCR** on the cropped
   region, regardless of whether the underlying page has a native text
   layer -- the user asked for OCR on a specific visual region, not a
   text-layer lookup, so the native-text shortcut never applies to those
   two flows.
5. Docling's own built-in OCR backends are disabled (`do_ocr=False`) --
   PaddleOCR is the only character-recognition engine in this system.
   Verified live: left at its default, Docling downloads and runs RapidOCR
   on every image-only page on top of what this service does with
   PaddleOCR, duplicating the work for nothing.
6. Docling's table-structure model (TableFormer, `do_table_structure=True`)
   IS enabled -- unlike RapidOCR, it doesn't duplicate PaddleOCR's job,
   and it's the only way a `native_text` page's tables come back as a real
   grid instead of flat, ungrouped text lines. Only applies to
   `native_text` pages: a page routed to PaddleOCR (no text layer at all)
   can't have its table cells structured, since TableFormer has no text to
   work with either (PaddleOCR's own output has no table awareness).

Per-page detection is real, not heuristic: it checks whether Docling's own
converted `DoclingDocument.texts` has any entries anchored to that page
(`ProvenanceItem.page_no`) -- verified against the real installed
`docling-core` with an actual native-text PDF (2 real text items) and an
actual image-only PDF (0 text items). See `src/ocr_worker/layout.py`'s
`native_text_blocks_for_page`.

**No silent fallback on a Docling/PaddleOCR failure.** If Docling's
`DocumentConverter` can't be constructed, or `.convert()` throws, or
PaddleOCR throws, that exception propagates all the way to
`OcrPipeline`'s outer handler, which records it as a **real failure**:
`document_versions.ocr_status='failed'` and the actual exception message
in `processing_jobs.last_error`. There is no code path that catches one of
these errors and returns placeholder/empty OCR-shaped data instead.

## Output contract

Every OCR result (full-document, zone, or rubber-band) is **one JSON
object**, stored at the MinIO key referenced by
`ocr_outputs.extracted_text_ref`, and returned directly (same shape) in
`POST /ocr/process/{version_id}`'s HTTP response body:

```json
{
  "document_version_id": "...",
  "text": "... flattened plain text, all pages joined ...",
  "pages": [
    {
      "page_number": 1,
      "method": "native_text",
      "text": "...",
      "confidence": 1.0,
      "blocks": [{"text": "...", "bbox": [x0, y0, x1, y1], "confidence": 1.0}],
      "tables": [
        {
          "bbox": [x0, y0, x1, y1],
          "num_rows": 2,
          "num_cols": 3,
          "cells": [
            {"row": 0, "col": 0, "row_span": 1, "col_span": 1, "text": "Item", "bbox": [x0, y0, x1, y1], "column_header": true, "row_header": false}
          ]
        }
      ]
    }
  ],
  "fields": {
    "invoice_number": {"value": "INV-4471", "confidence": 0.95, "bbox": [x0, y0, x1, y1]}
  },
  "flags": {"low_confidence": false, "requires_review": false}
}
```

`fields` is `{}` when the document type has no configured zone/field
template -- OS-FR-005's structured extraction still runs a best-effort
regex pass over the flattened text in that case (invoice numbers, dates,
amounts, IDs), but never fabricates a field it didn't actually find.
`bbox` is `null` for a regex-derived field (a match over concatenated text
has no single spatial location); zone-derived fields always carry a real
bbox (the zone's own pixel box). All bboxes, from every method, are in the
same page-pixel coordinate space at `OCR_RENDER_DPI`.

`tables` is only ever non-empty for a `native_text` page, and only when
Docling's table-structure model actually found a table on it -- real
rows/columns/cells (with `row_span`/`col_span` for merged cells, and
`column_header`/`row_header` flags), not the flat reading-order guess
`blocks` uses everywhere else. **A table's cell text is also present in
`blocks`/`text`, in row-then-column order** (see
`pipeline.py`'s `_merge_native_text_and_tables`) -- `tables` is additive
structure for a consumer that wants the real grid, not the only place a
table's content appears. A cell's `bbox` can be `null` if Docling
reasoned about the cell structurally without anchoring it to real page
geometry (e.g. a removed spanned cell).

The RabbitMQ event (`document.version.ocr.completed`/`.failed`) is
deliberately slimmer than this -- see `docs/events/ocr-scanning-events.md`
-- it carries `extracted_text_ref` and `flags` so a downstream consumer
knows to go fetch the detail, not a copy of the whole JSON.

## Structured JSON extraction (local LLM)

After a full-document OCR run commits, ocr-worker turns the OCR output into
structured JSON with a **local** 7B-class model. Label→value, table
header→cell and heading→section relationships are kept, and invented
values are replaced with `null`.

```
document -> upload validation -> Docling (native text, layout, labels, tables)
         -> PaddleOCR (pages without a text layer)      [unchanged OCR pipeline]
         -> OCR output JSON committed (ocr_outputs, MinIO, outbox)
         -> normalized representation (structuring/document_model.py)
         -> chunks sized to the model context (structuring/chunking.py)
         -> local LLM, JSON-Schema-constrained decoding (structuring/llm_client.py)
         -> JSON parse + schema validation, repair retry (structuring/schema.py)
         -> grounding check: values must occur in the source (structuring/grounding.py)
         -> merge chunks + final schema validation (structuring/service.py)
         -> ocr/{tenant}/{version}/{ocr_output_id}.structured.json   (MinIO)
         -> dms-core GET /api/v1/documents/{documentId}/structured-data
```

**What the model sees.** It never gets the flattened `text`. Each page is
sent as visual rows in reading order with their pixel position
(`L3 @167,356: Employee Name: | John Silva`). Blocks that sit side by side
on one row become ` | `-separated parts. Docling labels mark titles, section
headings, page headers/footers and list items (for OCR pages with no
labels, an ALL-CAPS heuristic is used). Tables are real header + row/column
grids: malformed grids are repaired and flagged, and a header row is
inferred when TableFormer didn't mark one. Key/value hints are added where
a label and its value are separate blocks (value below the label, or
multi-column form rows), and lines with low OCR confidence are marked `?`.
For native-text pages, PyMuPDF's per-line text (the new `pages[].lines`
in the OCR output, additive) replaces Docling's paragraph blocks, which
merge borderless label/value regions into a single paragraph. Page images
are never sent.

**Output** (validated against `StructuredResult` before it is stored or
returned):

```json
{
  "schema_version": 1,
  "status": "completed",
  "document_type": "employee_contract",
  "fields": {"employee_name": "John Silva", "employee_id": "EMP-1023", "joining_date": "2026-09-01", "salary": "LKR 150,000"},
  "tables": [{"name": "Salary Breakdown", "page": 1, "headers": ["Component", "Monthly Amount (LKR)"], "rows": [["Basic Salary", "120,000"]]}],
  "sections": [{"heading": "Terms and Conditions", "content": "1. Probation period: ..."}],
  "warnings": [{"code": "UNGROUNDED_VALUE", "message": "value not found in the document text; replaced with null", "path": "fields.manager"}],
  "error": null,
  "model": {"provider": "ollama", "name": "qwen2.5:7b-instruct"},
  "stats": {"pages": 1, "chunks_processed": 1, "llm_calls": 1, "prompt_tokens": 1650, "completion_tokens": 310, "duration_ms": 41000}
}
```

`status` is one of:

- `pending`: queued.
- `completed`.
- `partial`: some chunks failed, or the document exceeded `LLM_MAX_CHUNKS`.
- `failed`: see `error`.
- `skipped`: there was nothing to structure.

**Error handling.** Every failure has a stable `error.code` or
`warnings[].code`:

| code | when | behaviour |
|---|---|---|
| `UNSUPPORTED_FORMAT` / `CORRUPTED_DOCUMENT` / `ENCRYPTED_DOCUMENT` / `EMPTY_DOCUMENT` / `DOCUMENT_TOO_LARGE` | upload validation (`validation.py`), before Docling/PaddleOCR parse anything: real file signature vs. declared mime type, truncated/unreadable PDF, password, size/page limits | the **OCR** run fails with this prefix in `processing_jobs.last_error` |
| `EMPTY_OCR_RESULT` | OCR found no text anywhere | `skipped`, the model is not called |
| `MODEL_UNAVAILABLE` | model server unreachable, model not pulled, not enough memory to load it | `failed`, stops at the first chunk |
| `LLM_TIMEOUT` | no reply within `LLM_TIMEOUT_SECONDS` | `failed`, stops at the first chunk |
| `INVALID_JSON` / `SCHEMA_VALIDATION_FAILED` | reply isn't JSON / has the wrong shape | retried with a repair note (`LLM_MAX_RETRIES`) |
| `INCOMPLETE_RESPONSE` / `CONTEXT_OVERFLOW` | reply cut off at `LLM_MAX_OUTPUT_TOKENS`, or the prompt filled the context | the chunk is split in half and retried, recursively |
| `DOCUMENT_TOO_LARGE` (warning) | more chunks than `LLM_MAX_CHUNKS` | `partial`, first N chunks only |
| `MALFORMED_TABLE` (warning) | grid repaired, or ragged rows from the model | kept, padded with null |
| `UNGROUNDED_VALUE` (warning) | value not found in the chunk's text | nulled (`LLM_GROUNDING_POLICY=nullify`) |
| `LLM_ENDPOINT_NOT_LOCAL` | `LLM_BASE_URL` is a public host | the model is never called |

Structuring can never fail, roll back or delay OCR. It runs after the OCR
transaction commits, on its own bounded thread pool, and its failures are
recorded only on its own result.

**Security.** Inference is local only. `LLM_BASE_URL` must be loopback, a
private IP, or a single-label or internal (`.internal`, `.svc`, `.local`)
host unless it is explicitly allow-listed. `HTTP(S)_PROXY` is ignored for
model calls. Logs carry counts, durations and error codes only, never
document text, prompts, model replies or extracted values; error and
warning messages name JSON paths, not values. In compose, inference runs in
the dedicated `llm` service: no published port, and its only network is
`llm-internal` (`internal: true`, no gateway), shared with ocr-worker alone.
So the inference server cannot send document text off the host even if
misconfigured. The model download runs in a separate one-shot
`llm-model-init` container, which never sees a document.

**Performance.** One model, loaded once and kept resident
(`LLM_KEEP_ALIVE`), with one document at a time (`LLM_MAX_CONCURRENCY=1`).
Large documents are processed in page-ordered chunks sized from
`LLM_CONTEXT_TOKENS` (Ollama otherwise truncates prompts silently).
Temperature is 0 with a fixed seed, so re-runs are reproducible.

**APIs** (all require `X-Internal-Token`):

- `GET /ocr/structured/{version_id}`: newest result; 404 if none.
- `POST /ocr/structured/{version_id}` `{"wait": false}`: re-structures the
  newest stored OCR output without re-running OCR. Returns 202 with the
  `pending` result, or 200 with the final one when `wait: true`. Returns 409
  when disabled or when there is no OCR output yet.
- `GET /ocr/structured/health`: model server reachable and `LLM_MODEL`
  pulled.
- In `LLM_EXTRACTION_MODE=sync`, `POST /ocr/process/{id}` also returns the
  result under `structured`.

dms-core passthrough: `GET` / `POST /api/v1/documents/{documentId}/structured-data`
(tenant-checked, latest version).

**Model setup.** The default is `qwen2.5:7b-instruct` (Apache-2.0, ~4.7GB
Q4_K_M). `docker compose up` starts the `llm` service (Ollama; image
`LLM_IMAGE`, default `ollama/ollama:0.34.4`), and `llm-model-init` pulls
`LLM_MODEL` into the shared `ollama_models` volume once. ocr-worker calls
it at `http://llm:11434`. On a CPU-only host, `LLM_IMAGE=alpine/ollama:0.34.2`
runs the same Ollama server without the ~3.7GB of bundled GPU libraries.
For an NVIDIA GPU (official image):
`docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d`.
To swap models, set `LLM_MODEL` (e.g. `llama3.1:8b`, `mistral:7b-instruct`);
nothing else names the model. For llama.cpp's `llama-server`, vLLM or LM
Studio instead of Ollama, set `LLM_PROVIDER=openai_compatible` and point
`LLM_BASE_URL` at it.

| env var | default | meaning |
|---|---|---|
| `LLM_EXTRACTION_MODE` | `off` (compose: `async`) | `off` / `async` (after OCR, background) / `sync` (dev: block and return it) |
| `LLM_PROVIDER` | `ollama` | `ollama` or `openai_compatible` |
| `LLM_BASE_URL` | `http://localhost:11434` | model server (compose: `http://llm:11434`) |
| `LLM_MODEL` | `qwen2.5:7b-instruct` | model name/tag |
| `LLM_CONTEXT_TOKENS` / `LLM_MAX_OUTPUT_TOKENS` | `8192` / `2048` | context requested per call / reply budget |
| `LLM_CHUNK_MAX_TOKENS` | `0` (derived) | cap on chunk input size |
| `LLM_CHARS_PER_TOKEN` | `3.0` | conservative token estimate for chunking |
| `LLM_MAX_CHUNKS` | `20` | beyond this, the result is `partial` |
| `LLM_TIMEOUT_SECONDS` | `600` | per model call |
| `LLM_MAX_RETRIES` | `1` | repair retries per chunk |
| `LLM_GROUNDING_POLICY` | `nullify` | `nullify` / `flag` / `off` |
| `LLM_KEEP_ALIVE` | `30m` | how long Ollama keeps the model loaded |
| `LLM_MAX_CONCURRENCY` | `1` | documents structured at once |
| `LLM_WARMUP_ON_STARTUP` | `false` | load the model at worker start |
| `LLM_ALLOWED_HOSTS` / `LLM_ALLOW_NON_LOCAL` | empty / `false` | escape hatches for the locality guard |
| `MAX_DOCUMENT_BYTES` / `MAX_DOCUMENT_PAGES` | `52428800` / `500` | upload validation limits |

## Zone / structured-field templates

Stored in Postgres (`document_type_ocr_zones` --
`docs/database/V11__create_ocr_templates.sql`), one row per named zone,
keyed by `document_type_id`. A zone's `field_name` **is** its
structured-field mapping (OS-FR-005), so this one table covers both
OS-FR-003 (zone OCR) and OS-FR-005 -- no separate "field template" table.
Coordinates are fractions of page width/height (0.0-1.0), not pixels, so a
template defined once applies regardless of scan resolution.

A document type with no rows in this table falls back to full-document OCR
(the alternate flow "document type does not match any configured zone OCR
template").

## Known upstream gap

**`backend/workers/scan-worker/` does not exist yet.** Section 2.2 requires
OCR to never run on a version until it has been scanned clean, and this
service's queue consumer is built against that precondition -- but nothing
in this codebase currently flips `document_versions.scan_status` to `clean`
or publishes the event below. See `docs/events/ocr-scanning-events.md` for
the full contract (routing keys, DLQ convention), flagged there as assumed
pending scan-worker's implementation.

Two things exercise the full pipeline without scan-worker -- both have to
run on the docker network (no published host port, see above):

1. **`scripts/dev/publish_fake_scan_event.py`** -- bootstraps a fixture
   document/version, uploads a sample file to MinIO, and publishes
   `document.version.scan.completed` by hand.
2. **`POST /ocr/process/{version_id}`** -- runs the identical pipeline
   synchronously, bypassing RabbitMQ, returning the full output JSON
   directly.

Both funnel through the exact same `OcrPipeline` code as the real queue
consumer -- the trigger source never changes the processing logic.

## Local setup

```bash
cd backend/workers/ocr-worker
python -m venv .venv && source .venv/bin/activate   # or .venv\Scripts\activate on Windows
pip install -e ".[dev]"
```

Bring up the stack (no ocr-worker host port, so you can't curl `localhost:8090`
from the bare host -- see "Auth & service exposure"):

```bash
cd infra/local
docker compose up -d postgres rabbitmq minio ocr-worker
```

## Required verification

The real deliverable, per the task this was built against: proof
PaddleOCR and Docling are actually invoked, not a description of expected
output.

### 1. Sanity-check the call sites exist

```bash
grep -r "PaddleOCR(" backend/workers/ocr-worker/src
grep -r "DocumentConverter(" backend/workers/ocr-worker/src
```

Both must return real, non-test call sites (`src/ocr_worker/ocr_engine.py`
and `src/ocr_worker/layout.py` respectively) -- zero results means
something regressed into a stub.

### 2. Run the smoke test against the three real fixtures

`tests/fixtures/` has three real files (not 1x1 placeholders): a
native-text PDF (`native_text.pdf`), a scanned/image-only PDF
(`scanned_image_only.pdf`, a real raster embedded with no text layer at
all), and a plain scanned image (`scanned_image.png`).

```bash
docker run --rm --network local_default \
  -v "$(pwd)/tests/fixtures:/fixtures" \
  -v "$(pwd)/../../../scripts:/scripts" \
  -e INTERNAL_SERVICE_TOKEN=<value from infra/secrets/ocr-worker.secrets.env> \
  python:3.12-slim bash -c "
    pip install --quiet psycopg[binary] minio requests
    python /scripts/dev/smoke_test_ocr.py --fixtures-dir /fixtures
  "
```

This prints the **real** output JSON for each fixture to stdout --
`native_text.pdf`'s pages should show `"method": "native_text"` with
`"confidence": 1.0` and zero PaddleOCR calls; the two scanned fixtures
should show `"method": "paddleocr"` with real detected text/bboxes/
confidence scores from actually running the model.

### 3. Unit tests

```bash
pip install -e ".[dev]"
pytest
```

Covers, among others: (a) the native-text path never calls PaddleOCR, (b)
the image-only path calls PaddleOCR with real image data and produces
non-empty `blocks`, (c) confidence below threshold sets
`flags.requires_review`, (d) the DB transaction + outbox write happens
atomically (mocked DB/MinIO/RabbitMQ; the transactional-write tests run
against a real in-memory SQLite database via SQLAlchemy, so that sequence
is actually exercised, not just asserted against mocks). None of the unit
tests need a live scan-worker, RabbitMQ, MinIO, Postgres, or the real
PaddleOCR/Docling models -- they run in well under a second.

### On-demand zone / rubber-band OCR

```bash
curl -X POST http://ocr-worker:8090/ocr/zone \
  -H "X-Internal-Token: $INTERNAL_SERVICE_TOKEN" -H 'content-type: application/json' \
  -d '{"version_id": "<version_id>", "zone_names": ["invoice_number", "invoice_date"]}'

curl -X POST http://ocr-worker:8090/ocr/rubber-band \
  -H "X-Internal-Token: $INTERNAL_SERVICE_TOKEN" -H 'content-type: application/json' \
  -d '{"version_id": "<version_id>", "page": 0, "x0": 0.1, "y0": 0.05, "x1": 0.4, "y1": 0.12}'
```

(From a container on the same docker network -- e.g. the same
`docker run --network local_default ...` pattern as the smoke test.)
`x0/y0/x1/y1` are fractions of page width/height (0.0-1.0), not pixels, so
a selection made against a preview image at any resolution maps correctly
onto the page PyMuPDF actually renders.

## Configuration

See `src/ocr_worker/config.py` for the full list; the ones worth knowing about:

| env var | default | meaning |
|---|---|---|
| `MIN_CONFIDENCE_THRESHOLD` | `0.95` | OS-NFR-001; below this, `flags.low_confidence`/`requires_review` are set rather than silently accepted -- never dropped |
| `PREFETCH_COUNT` / `WORKER_POOL_SIZE` | `4` / `4` | OS-NFR-004: RabbitMQ prefetch and the thread-pool size backing it, so multiple documents process concurrently instead of one file blocking the queue |
| `INTERNAL_SERVICE_TOKEN` | (see `infra/secrets/ocr-worker.secrets.env`) | required `X-Internal-Token` header value on every `/ocr/*` request |
| `DB_URL` | points at `dms_core_db` | see "Database access" below |

## Database access

ocr-worker connects directly to the same Postgres instance/database as
dms-core (`dms_core_db`) to read/write `document_versions` (the `ocr_status`
column only), `document_type_ocr_zones` (read-only), and to write
`ocr_outputs`, `processing_jobs`, and `outbox_events` -- reusing dms-core's
existing outbox + `OutboxRelay` mechanism rather than publishing completion
events to RabbitMQ directly.

**Known limitation:** local dev config defaults to a dedicated
`ocr_worker_user` credential, but no migration/init script in this repo
actually creates that role or grants it table-level access yet -- today it
reuses `dms_core_user`'s credentials. Flagging this rather than silently
defaulting to the shared credential without comment.

## Secrets

Own file, `infra/secrets/ocr-worker.secrets.env` (plaintext, dev-only),
distinct from `infra/secrets/dms-core.secrets.env` -- ocr-worker needs none
of dms-core's secrets directly. Contains `INTERNAL_SERVICE_TOKEN`. No
encrypted counterpart exists yet (that requires the `bao` CLI and a token
against the real OpenBao transit key, neither available in this
environment) -- whoever has that access should run
`scripts/openbao-encrypt-secrets.sh infra/secrets/ocr-worker.secrets.env infra/secrets/ocr-worker.secrets.enc.env`
once.

`MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` (for the `minio` compose service,
which this repo also didn't have before) still live in
`infra/secrets/dms-core.secrets.env`, since MinIO itself is shared
infrastructure, not ocr-worker-specific.

## Docker

```bash
docker build -t oakdms-ocr-worker -f backend/workers/ocr-worker/Dockerfile backend/workers/ocr-worker
```

See `infra/local/docker-compose.yml`'s `ocr-worker` service entry --
no `ports:` (internal-only, see "Auth & service exposure"), its own
`env_file`, and named volumes for the PaddleOCR/Docling model caches
(`~/.paddlex`, `~/.cache/huggingface`) so they survive container restarts
instead of re-downloading from Hugging Face every time.

## What's out of scope here

- **scan-worker** itself (see "Known upstream gap" above).
- dms-core's passthrough `/ocr/*` routes and the real frontend upload/view
  page (see "Auth & service exposure") -- they depend on dms-core's
  `document`/`storage` modules, which don't exist yet.
- Owning the search index (OS-FR-006 says extracted data becomes searchable
  metadata; ocr-worker writes `ocr_outputs` + emits
  `document.version.ocr.completed` for a future indexing-worker to consume,
  it does not index anything itself).
- A template *editor* for `document_type_ocr_zones` -- the table exists,
  but nothing in this repo writes to it yet outside test fixtures/manual
  SQL.
