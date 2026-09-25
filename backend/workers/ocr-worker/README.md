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
- **Since Phase 1 the production trigger is not an HTTP call at all.**
  dms-core's upload/rerun writes a `document.version.ocr.requested` outbox
  event in the same transaction as the `document_versions` row, and
  ocr-worker consumes it off `ocr-worker.q.ocr-requested`. `POST
  /ocr/process/{version_id}` survives only as a dev-trigger and for the
  synchronous zone/rubber-band routes. See
  `docs/events/ocr-scanning-events.md` for the wire contract.
- Every `/ocr/*` route additionally requires an `X-Internal-Token` header
  matching `INTERNAL_SERVICE_TOKEN` (see `infra/secrets/ocr-worker.secrets.env`).
  This is defense in depth, **not** an authentication scheme -- the real
  boundary is the missing published port. `/healthz`/`/readyz` don't
  require it (container-orchestration probes only, low risk).
- dms-core's `document`/`storage` modules and the frontend upload/view page
  now exist, so the full `frontend -> dms-core -> queue -> ocr-worker` path
  is real. Anything that talks to ocr-worker directly still has to run *on
  the docker network*, not from the bare host, because there is no
  published port to reach from outside it.

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
OCR to never run on a version until it has been scanned clean, and the
`document.version.scan.completed` consumer is built against that
precondition -- but nothing in this codebase actually scans. dms-core's
`uploadDocument` sets `scan_status='clean'` directly at upload, which is
what makes the version eligible today; `get_version_for_processing` still
refuses anything not `clean`, so wiring a real scanner in later is a
producer change, not a change here. See `docs/events/ocr-scanning-events.md`
for the full contract (routing keys, wire format, DLQ convention), where
that one event is flagged as assumed pending scan-worker's implementation.

Two things exercise the pipeline without scan-worker -- both have to
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

### 3. Phase 1 exit criteria -- the queue path under load

`scripts/dev/smoke_test_ocr.py` and `scripts/dev/repro_batch.py` both drive
the **dev-trigger HTTP endpoint**, which is no longer how production
triggers OCR. `scripts/dev/phase1_verification.py` drives the real path:
it stages N versions in Postgres + MinIO, inserts
`document.version.ocr.requested` rows into `outbox_events` so dms-core's
own `OutboxRelay` publishes them, and then waits on the database for each
version's terminal state.

```bash
docker run --rm --network local_default \
  -v "$(pwd)/../../..:/repo" -v "<repro-fixtures>:/fixtures" \
  ocr-worker-test:latest \
  python /repo/scripts/dev/phase1_verification.py \
    --fixtures-dir /fixtures --total 50 --burst 10 \
    --report-json /fixtures/phase1-run.json
```

Fixtures come from `scripts/dev/build_fixtures.py` (it expands the three
committed fixtures into the 20-file mixed set: HEIC, multi-page and CCITT
G4 TIFF, rotated scans, 35-page PDFs, encrypted, corrupt, DOCX). The run
deliberately lies about `Content-Type` on a few items so magic-byte
sniffing is exercised on the live path too.

Use `--trigger direct` to publish straight to RabbitMQ with pika, which
isolates ocr-worker by bypassing the relay. Do **not** make that the
default: publishing that way is precisely how an earlier version of this
harness passed while dms-core's relay was double-encoding every payload
and both directions of the queue path were dead.

### 3b. Phase 1 close-out -- fault injection and the field path end to end

`scripts/dev/phase1_closeout_verification.py` covers what the load run
could not: selectable field extraction through the real upload API (with
dms-core's poll-time re-apply switched off, so only the event path can be
responsible for the result), a pool worker SIGKILLed mid-document, and a
per-page budget the document cannot meet. See
`docs/ocr/phase1-closeout.md` for the results.

Each check needs its own compose overlay, and refuses to run against the
wrong one rather than producing a pass for the wrong reason:

```bash
cd infra/local
docker compose -f docker-compose.yml -f docker-compose.closeout-fields.yml  up -d dms-core
docker compose -f docker-compose.yml -f docker-compose.closeout-reaper.yml  up -d ocr-worker
docker compose -f docker-compose.yml -f docker-compose.closeout-timeout.yml up -d ocr-worker
```

```bash
docker run --rm --network local_default \
  -v "<repo>:/repo" -v /var/run/docker.sock:/var/run/docker.sock \
  -e KEYCLOAK_ADMIN_PASSWORD=... \
  -e VERIFY_DSN=postgresql://postgres:...@postgres:5432/dms_core_db \
  ocr-worker-test:latest \
  python /repo/scripts/dev/phase1_closeout_verification.py fields \
    --report-json /repo/docs/ocr/phase1-closeout-fields.json
```

The docker socket mount is what lets the harness read pool-worker PIDs out
of the ocr-worker container and kill one -- it runs *inside* a container
(it has to, to resolve `dms-core`/`postgres` by service name) and that
container has no docker CLI. Put the stack back with plain
`docker compose -f docker-compose.yml up -d dms-core ocr-worker` when
done; the overlays are fault-injection settings, not ones to leave running.

### 4. Unit tests

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
| `FIELD_LOW_CONFIDENCE_THRESHOLD` | `0.6` | per-field bar (`extracted` vs. `low_confidence`), far lower than the document-level one above; the value is returned either way |
| `PREFETCH_COUNT` | *(= `OCR_WORKER_PROCESSES`)* | OS-NFR-004: how many deliveries RabbitMQ may have unacked with this worker at once. See "Prefetch" below |
| `OCR_WORKER_PROCESSES` | `2` | size of the `pebble` **process** pool that actually runs inference; one PaddleOCR + one Docling instance per process, never shared across threads |
| `OCR_WORKER_MAX_TASKS_PER_PROCESS` | `50` | recycle a pool worker after this many documents, bounding any per-call native-library leak |
| `OCR_TIMEOUT_BASE_S` / `OCR_TIMEOUT_PER_PAGE_S` | `60` / `25` | per-document budget is `base + per_page * the document's REAL page count`. See "Per-document timeout" below |
| `OCR_TIMEOUT_HARD_GRACE_S` | `30` | how long after the budget the in-worker hard watchdog fires, killing that one pool worker |
| `OCR_MAX_PAGES` | `50` | `TOO_MANY_PAGES` above this; sized so one document can't monopolize a pool worker for the better part of an hour |
| `OCR_MAX_FILE_SIZE_BYTES` | `104857600` | `FILE_TOO_LARGE` above this |
| `OCR_LEASE_DURATION_S` | `1800` | how long a claimed `processing_jobs` row's lease is valid before the reaper may reclaim it. Also the grace the reaper gives a `processing` row that has *no* lease at all before treating it as expired — see "Every `processing` row has a lease" below |
| `OCR_REAPER_INTERVAL_S` | `30` | how often the lease reaper sweeps for expired leases |
| `OCR_REAPER_BACKOFF_BASE_S` | `30` | linear backoff: a job's Nth requeue waits `N * this` before becoming claimable again |
| `MAX_DELIVERY_ATTEMPTS` | `5` | after this many attempts the reaper dead-letters the job and sets `ocr_status='failed'` |
| `OCR_RENDER_DPI` | `200` | page raster DPI for OCR (Phase 2 raises this to 300 with tiling) |
| `OCR_LANGUAGES` | `en` | PaddleOCR language set |
| `INTERNAL_SERVICE_TOKEN` | (see `infra/secrets/ocr-worker.secrets.env`) | required `X-Internal-Token` header value on every `/ocr/*` request |
| `DB_URL` | points at `dms_core_db` | see "Database access" below |

### Memory — measured, and now bounded

`docker stats` sampled every 3s across the full 50-upload Phase 1 run (368
samples, `OCR_WORKER_PROCESSES=2`):

| min | median | p95 | **peak** |
|---|---|---|---|
| 3.04 GiB | 4.60 GiB | 5.77 GiB | **6.89 GiB** |

`infra/local/docker-compose.yml` sets **`mem_limit: 8704m` (8.5 GiB)** on
`ocr-worker` — that peak plus ~25% headroom. Before this the container ran
with `HostConfig.Memory=0`: nothing bounded it at all, and it had already
been OOM-killed once during load testing (which is why the service also
carries `restart: unless-stopped`). A cgroup limit turns "the host runs out
of memory and the kernel picks a victim" into "this one container is killed
and restarted".

Each pool worker holds its own Docling layout model plus PaddleOCR det/rec
models resident, so budget roughly **3.4 GiB per `OCR_WORKER_PROCESSES`
plus a ~1.5 GiB shared/parent floor** and raise the limit with the pool
size. Re-measure rather than extrapolating if you change `OCR_RENDER_DPI`
or the model set.

### Prefetch

`PREFETCH_COUNT` defaults to **`OCR_WORKER_PROCESSES`**, not to a fixed
number. Phase 1 shipped a flat `4` against a pool of `2`, which the exit-
criteria run confirmed live: RabbitMQ held exactly 4 deliveries unacked
throughout. Two of those were messages this worker had no free pool process
to start — held, invisible to any other consumer, for as long as the two
running documents took (up to ~1000s for a 35-page scan in that run).
Prefetch above the pool size buys no throughput, because the pool and not
the queue is the bottleneck; it only delays redistribution. An explicit
`PREFETCH_COUNT` still overrides the default if you want a deliberate
buffer.

One caveat: `ScanCompletedConsumer` and `OcrRequestedConsumer` each set QoS
on their own channel, so with both connected the process-wide ceiling is
`2 x PREFETCH_COUNT`. That is currently moot — nothing publishes
`document.version.scan.completed` until scan-worker exists (see "Known
upstream gap") — but it is the thing to revisit when it does.

### Per-document timeout

The budget is `OCR_TIMEOUT_BASE_S + OCR_TIMEOUT_PER_PAGE_S × the
document's real page count`, read the instant PyMuPDF has opened the file
and **before** Docling's conversion runs. At the defaults a one-page photo
gets 85s and a 50-page document gets 1310s.

Phase 1 sized one uniform ceiling from `OCR_MAX_PAGES` instead — 1310s for
*every* document, one-page photos included — because the dispatching side
cannot know a page count without doing the pool worker's own job first.
With `OCR_WORKER_PROCESSES=2`, a single hung one-page upload therefore took
half the service's capacity out for 22 minutes. Three layers replace it
(`deadline.py`):

1. **Soft**, checked between pages. Raises `OcrTimeoutError`, which rides
   the ordinary failure path: `TIMEOUT: ...` in `processing_jobs.last_error`,
   `ocr_status='failed'`, a `.failed` event — and the pool worker survives
   to take the next document.
2. **Hard**, a watchdog thread armed alongside the soft deadline and firing
   `OCR_TIMEOUT_HARD_GRACE_S` later. A single wedged native
   PaddleOCR/Docling call never returns to a soft check and Python cannot
   interrupt it, so the watchdog writes the same `TIMEOUT` record itself and
   then `os._exit`s. `pebble` replaces that one worker; the rest of the pool
   is untouched. The exit code (`OCR_TIMEOUT_EXIT_CODE`) is what lets the
   parent tell this apart from an OOM kill, where no record was written and
   the reaper still owns the lease.
3. **The `pebble` `schedule(timeout=...)` ceiling**, sized above the largest
   budget layer 2 can possibly arm. It only fires if the watchdog thread
   never got scheduled at all.

**`OCR_LEASE_DURATION_S` must stay larger than
`OCR_TIMEOUT_BASE_S + OCR_MAX_PAGES × OCR_TIMEOUT_PER_PAGE_S + 2 ×
OCR_TIMEOUT_HARD_GRACE_S`** (1370s against the default 1800s lease), or the
reaper stops backing the worker up and starts competing with it: the lease
expires mid-inference, the reaper requeues, the retry restarts the same
document from scratch, and a document that was never failing can livelock
until `MAX_DELIVERY_ATTEMPTS` dead-letters it. ocr-worker logs a warning at
startup when that does not hold. (Observed for real during Phase 1
close-out, against a deliberately shortened lease.)

### Every `processing` row has a lease

`processing_jobs.lease_expires_at` is NULL for every status *except*
`processing`: a `queued` row has no lease yet, and a completed/failed/
dead-lettered one has none any more. A `processing` row without one is a
bug, and `V37__processing_jobs_require_lease.sql` enforces that with
`CHECK (status <> 'processing' OR lease_expires_at IS NOT NULL)`.

The constraint exists because such a row is **invisible to the reaper**:
its scan is `lease_expires_at < now()`, and in SQL a comparison against
NULL is NULL, not true. So the row never gets requeued, never gets
dead-lettered, and keeps holding V36's `uq_processing_jobs_active_ocr_ref`
slot — which blocks every future OCR job for that version. Four such rows
had accumulated in the local dev database, all written by one test
fixture; `claim_job`, the only production writer of that status, has
always set the lease in the same UPDATE.

The reaper also treats a leaseless `processing` row older than
`OCR_LEASE_DURATION_S` (by `COALESCE(started_at, created_at)`) as expired,
and logs a `WARNING` naming the row. That is a backstop for anything that
predates the constraint, not a supported state.

`WORKER_POOL_SIZE` no longer exists. It configured a `ThreadPoolExecutor`
sharing one PaddleOCR and one Docling instance across threads, which
`docs/ocr/failure-analysis.md` #2 measured at a 2.7x slowdown under only 4
concurrent requests (GIL + native-library contention). `OCR_WORKER_PROCESSES`
replaces it, and the two are not interchangeable: it sizes real OS
processes, so raising it multiplies resident model memory.

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
