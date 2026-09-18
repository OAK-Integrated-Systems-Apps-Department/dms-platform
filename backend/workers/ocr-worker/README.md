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
