# Prompt: Production-grade OCR & Field Extraction for Oak DMS

> Paste everything below this line into your coding agent (Claude Code, Cursor, etc.) at the root of the `dms-platform` repo.

---

You are a senior engineer taking the Oak DMS OCR module (`backend/workers/ocr-worker`, Python/FastAPI/Docling/PaddleOCR, plus its callers in `backend/dms-core`, Java/Spring) from "works on the fixtures" to production grade. The goal is OCR and field extraction that is as accurate as the documents allow, never crashes on a real-world upload, never silently loses a document, and always tells the user honestly what it could and could not read.

"Perfect" is not a measurable target, so treat it as: meet or beat OS-NFR-001 (≥95% field accuracy on standard layouts) measured against a real ground-truth corpus, and route everything below that bar to human review with the exact field and region highlighted. Never fabricate a value to look complete.

## 0. Read first, change nothing yet

Read these before writing any code, and keep their existing design principles (no silent fallbacks, failures always recorded, manual metadata overrides never clobbered by OCR, tenant isolation, outbox pattern for events):

- `docs/requirement-registry.md` → section "OCR & Scanning" (OS-FR-001..006, OS-NFR-001..006, workflow, alternate flows)
- `docs/events/ocr-scanning-events.md`
- `backend/workers/ocr-worker/README.md` (especially "Output contract" and "Native-text vs. PaddleOCR decision logic")
- `backend/workers/ocr-worker/src/ocr_worker/` — `pipeline.py`, `layout.py`, `ocr_engine.py`, `extraction.py`, `consumer.py`, `api.py`, `repository.py`, `config.py`
- `backend/dms-core/src/main/java/com/oak/dms/document/DocumentServiceImpl.java` (upload + `triggerOcrAsync` + `getOcrStatus`)
- `backend/dms-core/src/main/java/com/oak/dms/document/DocumentMetadataServiceImpl.java` (`applyOcrFields`)
- `backend/dms-core/src/main/java/com/oak/dms/ocrworker/*`
- Flyway migrations `V32`–`V35` in `backend/dms-core/src/main/resources/db/migration/`
- `infra/local/docker-compose.yml` (ocr-worker service)
- `frontend/components/documents/document-upload-form.tsx`, `document-fields-form.tsx`, `frontend/lib/documents/api.ts`

## 1. Reproduce and classify the current upload failures

Before fixing anything, reproduce the errors users are hitting. Bring up the stack, upload a varied batch (native PDF, scanned PDF, phone photo JPEG, iPhone HEIC, multi-page TIFF, rotated scan, large 30+ page PDF, password-protected PDF, DOCX, and 4 files at once), then collect `docker compose logs ocr-worker dms-core`, `processing_jobs.last_error`, `document_versions.ocr_status`, and `docker inspect oakdms-ocr-worker` (OOMKilled flag). Write a short `docs/ocr/failure-analysis.md` mapping each observed error to its root cause. Code review already points to these likely causes — confirm or refute each with evidence:

1. **Synchronous HTTP on the upload path.** `triggerOcrAsync` calls `POST /ocr/process/{id}` with a 60s read timeout, bypassing RabbitMQ. Long documents time out, dms-core marks the version `failed`, and the worker keeps running and may later write `completed` — a race. If the worker OOM-restarts mid-job, the version can stay `processing` forever.
2. **Shared, non-thread-safe models.** One `PaddleOCR` instance and one Docling `DocumentConverter` are shared across FastAPI's sync-endpoint threadpool *and* the consumer's `ThreadPoolExecutor`. The lazy init in `PaddleOcrEngine._engine_for` is also racy. Concurrent uploads can corrupt inference or multiply memory into an OOM kill.
3. **DB transaction held open during inference.** `process_version` wraps the whole OCR run inside `session_scope`, holding a Postgres transaction/connection for the full inference duration.
4. **Client-supplied MIME type is trusted.** `mimeType` comes from the browser's `Content-Type`. Values like `image/jpg`, `image/pjpeg`, `image/x-png`, `image/tif`, `application/octet-stream`, `image/heic`, `image/webp`, `image/bmp` all hit `UnsupportedMimeType`.
5. **Resolution cap too low for small print.** `render_page` caps the longest side at 1600px (~137 DPI on A4), which degrades recognition of small invoice text, totals, and tax IDs.
6. **Bounding-box coordinate spaces disagree.** PaddleOCR boxes are in the capped render space, while `native_text_blocks_for_page`/`native_text_lines_for_page` scale by the uncapped `dpi/72`. Highlights, zone crops, and spatial matching can be offset on large pages.
7. **Orientation disabled.** `use_doc_orientation_classify=False` assumes PyMuPDF renders at the correct orientation, which is false for sideways/upside-down scans and photos.
8. **Binary native-vs-scanned decision.** Any Docling text on a page marks it `native_text`, so hybrid pages (typed header + scanned body image), scanner-added junk text layers, and `(cid:NN)` glyph garbage are never OCR'd.
9. **Whole-PDF Docling conversion.** Large PDFs are converted in one call, causing memory spikes and long runtimes. Encrypted or corrupt PDFs produce raw exceptions instead of clear error codes.
10. **Hardcoded field confidences.** `extraction.py` returns fixed numbers (0.85, 0.9, 0.65…) unrelated to the OCR confidence of the tokens the value came from, and the 0.95 document threshold on a mean of line scores flags almost everything for review.

## 2. Target architecture

```
Upload (dms-core)
  → store original in MinIO (sanitized key) + document_versions row
  → outbox event document.version.ocr.requested (same transaction)
  → OutboxRelay → RabbitMQ
ocr-worker consumer (process pool, bounded concurrency)
  → claim job (short tx: status=processing, lease_expires_at, attempt++)
  → 1. Intake: magic-byte sniffing, normalization, conversion, limits
  → 2. Quality assessment per page
  → 3. Preprocessing per page (orientation, deskew, perspective, denoise, contrast)
  → 4. Routing per page/region: native text | OCR | hybrid
  → 5. OCR (PaddleOCR) + layout/table structure
  → 6. Field extraction (zones → anchors → spatial labels → patterns → optional LLM)
  → 7. Validation & cross-checks
  → 8. Confidence calibration & review flags
  → 9. Outputs: OCR JSON, searchable PDF, field results
  → commit (short tx: ocr_outputs, field results, status, outbox completed/failed)
dms-core consumer of document.version.ocr.completed/failed
  → applyOcrFields (never overwrites manual values) → notify user → indexing event
```

## 3. Requirements by stage

### 3.1 Triggering, concurrency, and reliability

- Replace the synchronous `OcrWorkerClient.processVersion` call on the upload path with an outbox event `document.version.ocr.requested`, carrying `tenant_id`, `document_id`, `version_id`, and `correlation_id`. The worker reads `requested_fields` from `document_versions.requested_fields` (already stored by V35) and resolves field specs from the schema; add a read-only query or have dms-core include the resolved specs in the event payload — pick one and document it.
- Keep `POST /ocr/process/{id}` for dev/smoke tests and for synchronous zone/rubber-band calls only. Make it enqueue-or-run behind the same bounded executor so it can never exceed the concurrency limit.
- dms-core consumes `document.version.ocr.completed` / `.failed` and calls `applyOcrFields` from there, replacing the HTTP-response-driven mapping. Remove the "reconcile on poll" workaround in `getOcrStatus` once this is in place.
- Run inference in a **process pool** (`concurrent.futures.ProcessPoolExecutor` or dedicated worker processes), one PaddleOCR and one Docling instance per process, initialized once at process start. Concurrency = `OCR_WORKER_PROCESSES` (default 2), sized from container memory. No model object is ever shared across threads.
- Pre-download all model weights at image build time and warm them up at process start; `/readyz` returns 503 until models are loaded.
- Short transactions only: claim, then run inference with no open transaction, then commit results. Use `SELECT ... FOR UPDATE SKIP LOCKED` or an idempotency check on `version_id` + `attempt` so redelivered messages don't double-process.
- Add a lease/heartbeat (`processing_jobs.lease_expires_at`, new migration). A reaper (in ocr-worker or a dms-core `@Scheduled` job) moves expired leases back to `queued` with backoff, and to `dead_letter` + `ocr_status=failed` after `max_delivery_attempts`. No version may remain `processing` indefinitely.
- Per-page timeout and per-document timeout (configurable). A timeout fails the job with a clear error code, never hangs a worker.
- Memory guard: cap pages per document (`OCR_MAX_PAGES`, default 200), process PDFs in page chunks (Docling `page_range`), free images after each page.

### 3.2 Intake and format support

- Detect the real type from magic bytes (`filetype` or `python-magic` in the worker; Apache Tika or a magic-byte check in dms-core at upload). Store the detected MIME type, not the browser's.
- Normalize aliases (`image/jpg`, `image/pjpeg`, `image/x-png`, `image/tif`, `image/x-tiff`).
- Support natively: PDF, PNG, JPEG, TIFF (including multi-page and CCITT G4 fax compression), BMP, WEBP, GIF (first frame), HEIC/HEIF (`pillow-heif`), DOCX.
- Convert via headless LibreOffice to PDF: DOC, ODT, RTF, XLS/XLSX, PPT/PPTX. If LibreOffice is not installed in the image, return `UNSUPPORTED_FORMAT` with the detected type in the message.
- Apply EXIF orientation for camera images before anything else.
- Handle encrypted PDFs (`ENCRYPTED_PDF`, no crash), corrupt/truncated files (`CORRUPT_FILE`, try PyMuPDF repair first), zero-page files, and oversize files.
- Sanitize `originalFilename` before building the MinIO object key in `DocumentServiceImpl` (strip path separators, control characters, normalize Unicode); keep the original name in the DB only.

### 3.3 Image quality assessment

Per rasterized page, compute and store: effective DPI, blur score (variance of Laplacian), contrast, and skew angle. If a page is below configurable thresholds, still attempt OCR, but set `flags.poor_image_quality=true` with a reason (`LOW_RESOLUTION`, `BLURRY`, `LOW_CONTRAST`), and surface the "please re-scan at higher resolution" message to the user per the alternate flow. Never reject a document purely for quality.

### 3.4 Preprocessing

Apply in this order, each step individually toggleable in config and recorded in the output (`pages[].preprocessing`):

1. Orientation correction (0/90/180/270) — enable PaddleOCR's `use_doc_orientation_classify`, or run a dedicated orientation classifier once per page.
2. Deskew (small-angle rotation from Hough lines or projection profile).
3. Perspective correction for camera photos (document-boundary detection with OpenCV) and optional `use_doc_unwarping` for curled pages, only when a photo is detected.
4. Denoising and shadow/background normalization.
5. Contrast enhancement (CLAHE).

Do not binarize by default; PaddleOCR generally performs better on grayscale/color. Benchmark before enabling any step by default (see section 5).

### 3.5 Rendering and coordinates

- Render at 300 DPI for OCR. Control memory through PaddleOCR's own detection limits (`text_det_limit_side_len`, `text_det_limit_type`), recognition batch sizes, and tiling large pages with overlap, rather than downscaling the whole page.
- For camera images, never upscale above native resolution.
- Every bbox in the output carries **normalized page coordinates** (0–1 fractions of page width/height) plus the page's width/height in points. Pixel boxes may remain for backward compatibility, but all consumers (zones, rubber-band, highlights, spatial matching) use the normalized form. Add a test proving a native-text bbox and an OCR bbox for the same word on the same page agree within tolerance.

### 3.6 Routing: native text, OCR, or hybrid

Replace the "any text on page → native" rule with a text-layer quality check per page:

- Text coverage: area covered by native text boxes vs. area covered by images.
- Garbage ratio: proportion of `(cid:NN)`, replacement characters, and non-printables.
- Plausibility: ratio of dictionary-like tokens for the configured languages.

Decide per page: `native_text` (good layer, little image area), `ocr` (no layer or garbage layer), or `hybrid` (good layer plus large image regions — OCR the image regions and merge results in reading order). Record the decision and its metrics in `pages[].routing`.

### 3.7 OCR and layout

- Keep PaddleOCR as the sole recognition engine. Make languages configurable per tenant and per document type (`ocr_languages` list), not a single global `en`. Note that PaddleOCR supports Tamil (`ta`) but has no official Sinhala model — if Sinhala documents are expected, flag it in the docs as a known gap and detect the script so those pages are routed to review rather than returning confident garbage.
- For scanned pages, add table structure recognition (evaluate PaddleOCR's PP-StructureV3 table module) so line items on scanned invoices get real rows/columns — today `tables` is only populated for native-text pages. Put it behind a flag and benchmark it.
- Keep word-level and line-level results separately (the current `WordResult` actually holds lines — rename or split it).

### 3.8 Field extraction

Keep the existing priority order and make each tier stronger:

1. **Zone templates** (`document_type_ocr_zones`) — unchanged behavior.
2. **Anchor-relative zones** — new: a zone defined relative to a label anchor ("value is to the right of / below 'Invoice No'"), which survives layout shifts between vendors. New migration + template JSON schema.
3. **Spatial label matching** — existing `_spatial_value_on_page`, now on normalized coordinates, with fuzzy label matching tolerant of OCR errors (`Inv0ice N0`, `lnvoice`).
4. **Pattern rules** — extend beyond US formats: currency symbols and codes including `Rs.`, `Rs`, `LKR`, `/=` suffix; thousands separators `,` `.` and space; comma decimals; negative amounts in parentheses; amounts in words as a cross-check. Keep day-first date parsing as a configurable per-tenant locale (the current code hardcodes it) and mark genuinely ambiguous dates (`03/04/2026` with no other signal) as `low_confidence`.
5. **Optional LLM structuring stage** — pluggable (`EXTRACTION_LLM_PROVIDER=none|local|anthropic`, default `none`, per-tenant opt-in because document content leaves the worker). Input: OCR lines with IDs and normalized bboxes. Output: strict JSON schema where every value must cite the `line_id`s it came from. Post-validate: a value whose text cannot be found in the cited lines (after normalization) is discarded. The LLM never overrides a higher-tier match; it only fills fields the deterministic tiers left `not_found`, and its results are capped at `low_confidence` unless validation rules pass.

Add invoice-specific extraction: line items (description, quantity, unit price, line total), subtotal, tax rate, tax amount, total, currency, vendor tax/VAT ID, due date, payment terms. Store line items in a new table (`document_ocr_line_items`, new migration) or as a JSON column — choose and justify.

Never clip, invent, or "correct" a value beyond deterministic normalization. Always store the raw OCR string alongside the normalized value.

### 3.9 Validation and cross-checks

Run after extraction, per document type; a failing rule sets the involved fields to `low_confidence` with a `validation_errors` entry, never drops them:

- `subtotal + tax ≈ total` (tolerance configurable); `Σ line_totals ≈ subtotal`; `qty × unit_price ≈ line_total`.
- Dates: parseable, within a plausible range, `due_date ≥ invoice_date`, `expiry_date ≥ effective_date` (existing rule).
- Currency is one of the field's configured options.
- Mandatory fields present (feeds the existing "highlight failed field, prompt manual correction" flow).
- Reuse dms-core's `MetadataValidationService` rules as the source of truth where possible so OCR and manual entry validate identically.

### 3.10 Confidence

Replace hardcoded field confidences with a computed score:
`field_confidence = mean OCR confidence of the source tokens × tier weight × validation factor`, where native text tokens count as 1.0. Calibrate tier weights and the `extracted` / `low_confidence` threshold on the evaluation corpus (section 5) so that fields marked `extracted` are actually correct ≥95% of the time. Replace the document-level `mean < 0.95` flag with: `requires_review = any mandatory field not extracted OR any validation failure OR poor image quality`.

### 3.11 Outputs and searchability

- Keep the existing output JSON contract and add fields only (add `"schema_version": 2`). `applyOcrFields` must keep working unchanged against the new shape.
- Generate a **searchable PDF rendition** (original image + invisible text layer) for every OCR'd document, store it in MinIO, and record it in `renditions`.
- Emit data an indexing worker can consume for full-text + field search (OS-FR-006); do not build the indexer itself.

### 3.12 Errors and user feedback

Define an error-code taxonomy stored in `processing_jobs.last_error` as `CODE: human message`, and returned in the failed event: `UNSUPPORTED_FORMAT`, `ENCRYPTED_PDF`, `CORRUPT_FILE`, `TOO_MANY_PAGES`, `FILE_TOO_LARGE`, `LOW_IMAGE_QUALITY`, `TIMEOUT`, `WORKER_CRASH`, `ENGINE_ERROR`. dms-core maps these to friendly messages; the frontend never shows a stack trace. Every failed or review-required document appears in a review queue.

### 3.13 Human review UI (frontend)

On the document view: page preview with extracted-field highlights drawn from normalized bboxes; each field shows value, confidence badge, and status; clicking a field scrolls to its region; the user can accept, edit, or draw a rubber-band region to re-extract that field (existing `/ocr/rubber-band`). Store corrections (original OCR value vs. user value) so accuracy can be measured from real usage.

## 4. Constraints

- CPU-only inference; no GPU dependencies in the image.
- New schema changes as Flyway migrations `V36__...` onward; never edit applied migrations.
- ocr-worker stays internal-only (no published port, `X-Internal-Token` kept).
- Unit tests must still run in seconds without real models (fakes for `LayoutEngine`/`OcrEngine`); model-dependent tests live in a separate, marked suite.
- All configuration via env vars in `config.py`, documented in the README table.
- Preserve every existing test; update only where a behavior change is intended and documented.

## 5. Evaluation harness (required — this is how "perfect" gets measured)

- Build `backend/workers/ocr-worker/eval/` with a ground-truth corpus: at least 30 documents per seeded type (INVOICE, CONTRACT, POLICY), covering native PDF, clean scan, low-DPI scan, skewed scan, rotated page, phone photo with perspective, shadowed photo, fax TIFF, multi-page, hybrid page, and non-English where applicable. Each has a JSON label file with expected text (for a subset) and expected field values.
- Metrics: character error rate and word error rate for text; per-field exact match, precision, recall; `extracted`-status precision (how often "extracted" is really correct); p50/p95 latency per page and per document; peak RSS.
- A `make eval` / `python -m eval.run` command prints a per-field and per-condition report and writes it to `eval/reports/`. Every preprocessing toggle and model option in section 3 must be justified by a before/after eval report.
- Add a CI gate that fails if field accuracy on the corpus drops below the recorded baseline.

## 6. Delivery plan

Work in this order and stop for review at the end of each phase with a summary, the eval report, and the test results:

1. **Stability** — failure analysis (section 1), queue-based triggering, process pool, short transactions, leases/reaper, MIME sniffing and format intake, error codes. Exit criteria: 50 mixed uploads including 10 concurrent produce zero crashes, zero stuck `processing` versions, and a clear status for every file.
2. **Accuracy foundation** — eval harness and baseline, 300 DPI rendering with tiling, normalized coordinates, quality assessment, preprocessing, per-page routing including hybrid.
3. **Extraction** — confidence calibration, pattern upgrades, anchor zones, invoice line items, validation rules, optional LLM tier.
4. **Outputs and UX** — searchable PDF rendition, review queue, field-highlight review UI, correction capture.

For each phase, update `backend/workers/ocr-worker/README.md` and `docs/events/ocr-scanning-events.md` to match reality, and list any requirement you could not meet and why.
