# OCR & Scanning event contract

Owner: `backend/workers/ocr-worker`. Traces to requirement-registry.md section
2.4 ("OCR & Scanning", OS-FR-001..006, OS-NFR-001..006) and the section 2.2
precondition that scanning must complete, clean, before OCR ever runs.

**Status: ASSUMED, pending scan-worker.** `backend/workers/scan-worker/` does
not exist yet -- nothing in this codebase currently flips
`document_versions.scan_status` to `clean` or publishes the event ocr-worker
consumes below. Everything in this document is ocr-worker's own proposal for
that contract, built so scan-worker (or whatever eventually owns malware
scanning) has something concrete to implement against, not a negotiated
agreement with that service. Treat wiring the real producer as a follow-on
integration task. Until then, the contract is validated via
`scripts/dev/publish_fake_scan_event.py` (publishes it by hand) and
`POST /ocr/process/{version_id}` (bypasses it entirely).

All events are published to the existing `oak.dms.events` topic exchange
(`RabbitMqConfig.DMS_EVENTS_EXCHANGE`), durable, no auto-delete -- ocr-worker
never declares that exchange with different parameters, only queues/bindings
against it, per the ownership model in `RabbitMqConfig.java`'s class comment.

## Consumed: `document.version.scan.completed`

Routing key: `document.version.scan.completed`
Queue (declared by ocr-worker): `ocr-worker.q.scan-completed`

| field | type | notes |
|---|---|---|
| `schema_version` | int | `1` |
| `tenant_id` | uuid | |
| `document_id` | uuid | |
| `version_id` | uuid | the `document_versions` row to process |
| `scan_status` | string | ocr-worker only processes `"clean"`; anything else is ack'd and skipped (never processed, never nacked -- it's not a delivery failure, the document just isn't eligible) |
| `correlation_id` | uuid, optional | propagated onto `processing_jobs.correlation_id` and the produced event(s) |

Consumer behavior:
- `scan_status != "clean"` -> ack, skip, log. Handles a hypothetical producer that publishes non-clean outcomes on the same key rather than only publishing on success.
- Missing/unparseable `version_id` -> nack without requeue (malformed message, requeueing can't fix it).
- Otherwise runs the same pipeline as `POST /ocr/process/{version_id}` (see ocr-worker's README). Ack only after its DB transaction commits; on pipeline failure (already recorded to `processing_jobs`/`document_versions`/`outbox_events` inside that transaction) nack without requeue, which routes to the dead-letter queue below.

## Produced: `document.version.ocr.completed`

Routing key: `document.version.ocr.completed`. Written to `outbox_events` in the same DB transaction as the `ocr_outputs` insert and `document_versions.ocr_status` update (workflow step 6) -- delivered later by dms-core's existing `OutboxRelay`, not published directly by ocr-worker.

| field | type | notes |
|---|---|---|
| `schema_version` | int | `1` |
| `tenant_id`, `document_id`, `version_id`, `ocr_output_id` | uuid | |
| `extracted_text_ref` | string | MinIO object key in `minio_ocr_output_bucket` -- the single JSON artifact (see ocr-worker's README "Output contract"), containing the flattened `text`, per-page `method`/`text`/`confidence`/`blocks`, `fields`, and `flags` |
| `page_count` | int | |
| `flags` | object | `{"low_confidence": bool, "requires_review": bool}` -- see "Open question: representing low confidence" below |
| `correlation_id` | uuid \| null | |
| `occurred_at` | ISO-8601 timestamp | |

Deliberately slim: the full per-page detail and structured `fields` live only in the MinIO artifact, not duplicated onto the event -- a downstream consumer (a future indexing step, OS-FR-006) fetches `extracted_text_ref` for that. ocr-worker writes the data; it does not own the search index itself.

## Produced: `document.version.ocr.failed`

Routing key: `document.version.ocr.failed`. Same outbox mechanism as above, written when the pipeline hits an unrecoverable error (bad/corrupt file, unsupported mime type, storage fetch failure, a Docling/PaddleOCR failure that couldn't be recovered, etc.) -- never for a low-confidence result, which is `.completed` with `flags.low_confidence: true` (see below). No silent fallback: a real Docling or PaddleOCR exception is what lands here, verbatim, in `error` and `processing_jobs.last_error` -- never swallowed into a placeholder success.

| field | type | notes |
|---|---|---|
| `schema_version` | int | `1` |
| `tenant_id`, `document_id`, `version_id` | uuid | |
| `error` | string | exception message, also written to `processing_jobs.last_error` |
| `correlation_id` | uuid \| null | |
| `occurred_at` | ISO-8601 timestamp | |

## Dead-letter convention

Exchange: `oak.dms.events.dlx` (topic, durable) -- declared by ocr-worker, distinct from the main `oak.dms.events` exchange so a bad message can't accidentally re-enter a live routing key.
Queue: `ocr-worker.q.dead-letter`, bound to `oak.dms.events.dlx` with routing key `document.version.ocr.dead-letter`.

The main queue (`ocr-worker.q.scan-completed`) is declared with `x-dead-letter-exchange`/`x-dead-letter-routing-key` arguments pointing at the above, so any message ocr-worker nacks-without-requeue lands there automatically rather than being silently dropped by RabbitMQ. Retry/backoff policy is driven by `processing_jobs.attempts` (incremented per delivery attempt), not by RabbitMQ requeueing -- a message is only ever nacked with `requeue=false`. This is a proposed convention pending confirmation from whoever owns the platform's overall DLQ/retry strategy; no other worker in this repo has one yet to match against.

## Open questions (need confirmation, not yet decided platform-wide)

**Routing key names.** `document.version.scan.completed` / `.ocr.completed` / `.ocr.failed` are ocr-worker's proposal, matching the `document.version.*` shape implied by `document_versions`. Not yet defined anywhere else in code or docs.

**Zone template storage -- resolved.** `document_type_ocr_zones` (`docs/database/V11__create_ocr_templates.sql`), one row per named zone, keyed by `document_type_id`; a zone's `field_name` doubles as its structured-field mapping (OS-FR-005), so one table covers both OS-FR-003 and OS-FR-005. This is a real schema change proposed by ocr-worker, outside this service's own ownership of `dms-core`'s Flyway migrations -- applied manually against the local dev database for now; a corresponding dated Flyway migration in `backend/dms-core/src/main/resources/db/migration/` is a follow-up once confirmed.

**`extracted_text_ref` format -- resolved.** **Structured JSON** (flattened `text` + per-page `method`/`text`/`confidence`/`blocks` + `fields` + `flags`), not plain text -- see ocr-worker's README "Output contract" for the exact shape. Rationale unchanged: OS-FR-005/006 need the structured detail downstream, and plain text alone would force a second round-trip through the source object to re-derive it.

**Representing a low-confidence result -- resolved.** Neither `ocr_outputs.status` nor `document_versions.ocr_status` has a dedicated enum value for "completed but flagged for review" (their CHECK constraints only allow `pending/processing/completed/failed/unsupported`). ocr-worker's decision: such a result is still `ocr_status = 'completed'` / `ocr_outputs.status = 'completed'` -- the data was produced and should not be treated as failed -- with `flags: {"low_confidence": true, "requires_review": true}` carried in the output JSON and the `document.version.ocr.completed` event payload. This is the cleanest fit for the existing schema; adding a distinct status value is a schema change outside this service's ownership. The two flags are currently always equal (no second, independent criterion for "requires review" beyond the confidence threshold exists yet).

**Ad-hoc zone/rubber-band requests update `document_versions.ocr_status`.** `POST /ocr/zone` and `POST /ocr/rubber-band` persist their result the same way the full pipeline does (an `ocr_outputs` row + `ocr_status = 'completed'`), since OS-FR-006 asks for extracted data to be searchable metadata regardless of trigger. A consequence: a one-off rubber-band pull on an already-OCR'd version overwrites `ocr_status` with the ad-hoc result's outcome rather than leaving the full-document result's status alone. `ocr_outputs` is append-only (one row per request) so no data is lost, but the "current" status column reflects whichever ran most recently. Flagged here as a tradeoff rather than silently decided.
