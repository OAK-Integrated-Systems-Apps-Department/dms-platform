# OCR & Scanning event contract

Owner: `backend/workers/ocr-worker`. Traces to requirement-registry.md section
2.4 ("OCR & Scanning", OS-FR-001..006, OS-NFR-001..006) and the section 2.2
precondition that scanning must complete, clean, before OCR ever runs.

**Status since Phase 1 (stability):** `document.version.ocr.requested`,
`.completed` and `.failed` are **live and verified end to end** on the local
stack -- they are the real production trigger and outcome path, replacing the
synchronous `POST /ocr/process/{id}` call that used to sit on dms-core's
upload thread. `document.version.scan.completed` remains **ASSUMED**:
`backend/workers/scan-worker/` still does not exist, nothing flips
`document_versions.scan_status` to `clean` except dms-core's own upload
(which sets it directly), and that one contract is still ocr-worker's
proposal rather than a negotiated agreement. It is exercised by hand via
`scripts/dev/publish_fake_scan_event.py`.

All events are published to the existing `oak.dms.events` topic exchange
(`RabbitMqConfig.DMS_EVENTS_EXCHANGE`), durable, no auto-delete -- ocr-worker
never declares that exchange with different parameters, only queues/bindings
against it, per the ownership model in `RabbitMqConfig.java`'s class comment.

## Wire format (binding on every producer and consumer here)

A message body on `oak.dms.events` is **the UTF-8 bytes of a JSON object**,
with `content_type: application/json` and no `__TypeId__` header. That is
not a stylistic note; it is the contract, and violating it took down both
directions of this path once already.

Neither service publishes to RabbitMQ directly: both write `outbox_events`
rows and dms-core's `OutboxRelay` does the publishing (see "Database access"
in ocr-worker's README). `outbox_events.payload` is a `jsonb` column, so it
is *already* serialized JSON. The relay used to hand that `String` to
`RabbitTemplate.convertAndSend`, whose `Jackson2JsonMessageConverter`
serialized it a second time into a quoted string literal
(`"{\"tenant_id\": ...}"`, header `__TypeId__=java.lang.String`). Observed
consequences on the live stack, both silent to the user:

- dms-core's `OcrEventsConsumer` takes `Map<String, Object>` and got a
  `String`: every `.completed` event failed with `MismatchedInputException`,
  retried, and dead-lettered. `applyOcrFields` never ran off the event.
  (`DocumentServiceImpl.getOcrStatus`'s poll-time re-apply masked this in the
  UI, which is why it went unnoticed -- and why that safety net must stay
  until this path has run under sustained real load.)
- ocr-worker's consumers do `json.loads(message.body)` and got a `str`:
  `body.get(...)` raised `AttributeError` straight out of the callback, so
  the delivery was neither acked nor nacked. RabbitMQ redelivered it on
  every reconnect, forever, each one permanently holding a prefetch slot.
  Three such messages were found still stuck after a full stack restart, and
  uploads made through the real API never triggered OCR at all.

Fixed on both sides, and both sides are covered by tests that fail if it
regresses:

- Producer: `OutboxRelay.jsonMessage` sends the payload bytes as-is, via
  `rabbitTemplate.send` rather than `convertAndSend`
  (`OutboxRelayWireFormatTest`).
- Consumer: ocr-worker dead-letters any body that is not a JSON object, and
  any delivery it cannot parse ids from, instead of letting an exception
  escape the callback. Every delivery now ends in an explicit ack or nack
  (`tests/test_consumer_poison_messages.py`).

No `__TypeId__` header is set on purpose: Spring AMQP's converter defaults to
`TypePrecedence.INFERRED`, so each listener's own parameter type governs
deserialization.

## Consumed: `document.version.ocr.requested`

Routing key: `document.version.ocr.requested`
Queue (declared by ocr-worker): `ocr-worker.q.ocr-requested`

**This is the production trigger.** Produced by dms-core
(`DocumentServiceImpl.queueOcrJobAndPublishRequested`, called from both
`uploadDocument` and `prepareRerun`) in the same transaction as the
`document_versions` row and a `queued` `processing_jobs` row -- and also by
ocr-worker's own lease reaper when it requeues an expired-lease job
(`repository.py#reap_requeue`). A consumer must handle it identically
regardless of which one produced it.

| field | type | notes |
|---|---|---|
| `schema_version` | int | `1` |
| `tenant_id`, `document_id`, `version_id` | uuid | |
| `correlation_id` | uuid \| null | propagated onto `processing_jobs.correlation_id` and the produced outcome event |
| `occurred_at` | ISO-8601 timestamp | |

Deliberately carries **no resolved field specs** (Phase 1 plan Decision 1):
ocr-worker re-reads `document_versions.requested_fields` and resolves the
schema itself at claim time
(`repository.py#resolve_requested_field_specs`, joining
`document_type_fields` at the document type's *current* schema version ->
`metadata_fields` -> `metadata_field_options`). A rerun that widens the
requested set while a run is already in flight is therefore picked up by
that in-flight run, without a second event.

> That resolution was specified here from the start but **was not
> implemented until Phase 1 close-out**. Until then the queue consumers
> dispatched with `requested_fields=None`, so the pipeline took the legacy
> generic-regex branch, `document_ocr_field_results` was never written by a
> real upload, and dms-core's `OcrEventsConsumer` had nothing to apply to
> `document_metadata_values`. Only the dev-trigger endpoint — which sends
> resolved specs in its request body — ever exercised the selectable-field
> path. Proven end to end by
> `scripts/dev/phase1_closeout_verification.py fields`.

An empty `requested_fields` column resolves to "no restriction", i.e. the
legacy zone-template + generic-regex pass, so a document uploaded without a
`requestedFields` parameter behaves exactly as before.

Consumer behavior:
- No `scan_status` check here, unlike `scan.completed`: `get_version_for_processing` already refuses anything whose `scan_status` isn't `clean`.
- Body that isn't a JSON object, missing `version_id`, or unparseable `version_id`/`correlation_id` -> nack without requeue (straight to the dead-letter queue; requeueing cannot fix a malformed message).
- Otherwise claims the job (`claim_job`, a compare-and-swap with a lease) and dispatches into the process pool. Ack only after the outcome transaction commits; a pipeline failure has already recorded `ocr_status='failed'` + `processing_jobs.last_error` + the `.failed` outbox event inside that transaction, so it is nacked without requeue and retry policy is governed by `processing_jobs.attempts`, never by RabbitMQ redelivery.
- A per-document timeout (`deadline.py`, sized from the document's real page count) records `TIMEOUT` itself, through the same failure path — it does **not** leave the lease for the reaper. Only the outer `pebble` ceiling, or a genuine crash/OOM kill, does that.

**Producer note — the reaper's retry event is emitted on a LATER tick than
the requeue.** `reap_requeue` moves the expired-lease job back to `queued`
with `next_attempt_at = now + backoff` and emits nothing; a subsequent
reaper tick finds it via `find_due_retries` once that backoff has elapsed
and emits the event then (`publish_due_retry`, which clears
`next_attempt_at` in the same CAS so the event can never be emitted twice).

The two used to happen together, and that silently dropped **every** retry:
`OutboxRelay` polls every 2s, so the event always reached the consumer
inside the backoff window, `claim_job`'s own `next_attempt_at <= now` guard
correctly refused the claim, the consumer acked and skipped — and nothing
ever published again. The job sat in `queued` with a long-elapsed
`next_attempt_at` forever, since `find_expired_leases` only looks at
`processing` rows. Caught live during Phase 1 close-out:

```
05:23:59  Reaper requeued job ... attempts=1/5, backoff=5s
05:24:01  Version ...: no claimable job; ack-and-skip
```

The larger the backoff, the more certain the drop, so the production 30s
backoff was strictly worse than the 5s one it was found with. A consumer of
this event does not need to care which producer emitted it — that part of
the contract is unchanged.

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
| `extracted_text_ref` | string | the object key dms-core stored the artifact under (it chooses the key when it accepts the worker's PUT -- see docs/ocr/internal-endpoint.md), through the ordinary encrypted storage layer. The single JSON artifact (see ocr-worker's README "Output contract"), containing the flattened `text`, per-page `method`/`text`/`confidence`/`blocks`, `fields`, and `flags` |
| `page_count` | int | |
| `flags` | object | `{"low_confidence": bool, "requires_review": bool}` -- see "Open question: representing low confidence" below |
| `correlation_id` | uuid \| null | |
| `attempt` | int | the claiming `processing_jobs` row's `attempts` at the time this outcome was recorded; a consumer compares it against the job's CURRENT `attempts` to skip an outcome the reaper has already superseded with a fresh retry, rather than trusting delivery order |
| `occurred_at` | ISO-8601 timestamp | |

Deliberately slim: the full per-page detail and structured `fields` live only in the stored artifact, not duplicated onto the event -- a downstream consumer (a future indexing step, OS-FR-006) fetches `extracted_text_ref` for that. ocr-worker writes the data; it does not own the search index itself.

### Per-page `quality` and `flags` (Phase 2 Step 5, added 2026-09-29)

The event payload is unchanged. What changed is the artifact at `extracted_text_ref`: every **rasterized** page now carries a `quality` block (`effective_dpi`, `blur_score`, `contrast`, `skew_angle`, and the `thresholds` they were judged against) and a per-page `flags` object.

Two page-level flags exist today:

| flag | meaning |
|---|---|
| `poor_image_quality` (+ `poor_image_quality_reasons`: `LOW_RESOLUTION` / `BLURRY` / `LOW_CONTRAST`) | the page is below a configured quality bar. It was still OCR'd -- production prompt 3.3 forbids rejecting a page for quality -- and this is what drives the "please re-scan at a higher resolution" message |
| `table_structure_skipped` | Docling's table pass was abandoned on its per-page deadline. The page kept all its text and lost only table structure |

Both are **page-level** and must not be confused with the document-level `flags` on the event payload above. `flags` carries only what is true, so an empty object reads as a clean page; a page with no `quality` block at all was never rasterized (a native-text page), which is a different fact from `{"measured": false}`.

**This is the second criterion "Open question: representing low confidence" said did not exist.** That note records `low_confidence` and `requires_review` as always equal, because confidence was the only input. A page flagged `poor_image_quality` is a real, independent reason to route a document to review even when its confidence is acceptable -- a washed-out scan can be read confidently and wrongly. Phase 3's `requires_review` rule is where the two stop being the same boolean; the flags it needs are now emitted.

## Produced: `document.version.ocr.failed`

Routing key: `document.version.ocr.failed`. Same outbox mechanism as above, written when the pipeline hits an unrecoverable error (bad/corrupt file, unsupported mime type, storage fetch failure, a Docling/PaddleOCR failure that couldn't be recovered, etc.) -- never for a low-confidence result, which is `.completed` with `flags.low_confidence: true` (see below). No silent fallback: a real Docling or PaddleOCR exception is what lands here, verbatim, in `error` and `processing_jobs.last_error` -- never swallowed into a placeholder success.

| field | type | notes |
|---|---|---|
| `schema_version` | int | `1` |
| `tenant_id`, `document_id`, `version_id` | uuid | |
| `error` | string | `CODE: human message` (see the error-code taxonomy in ocr-worker's `errors.py`), also written to `processing_jobs.last_error` |
| `correlation_id` | uuid \| null | |
| `attempt` | int | same staleness semantics as on `.completed` |
| `occurred_at` | ISO-8601 timestamp | |

Also produced by the lease reaper (`repository.py#reap_dead_letter`) when a
job exhausts `MAX_DELIVERY_ATTEMPTS`: it sets `processing_jobs.status
='dead_letter'` and `document_versions.ocr_status='failed'` and writes this
event in the same transaction. That is what actually guarantees "no version
stays `processing` forever" -- nothing else would move a version whose
worker was OOM-killed while holding the lease.

## Consumed by dms-core: `document.version.ocr.completed` / `.failed`

Queue (declared by dms-core, `RabbitMqConfig`): `dms-core.q.ocr-events`,
bound to `oak.dms.events` on both routing keys, dead-lettering to
`dms-core.q.ocr-events.dead-letter`. Handler: `OcrEventsConsumer`.

ocr-worker has already written `document_versions.ocr_status` and
`document_ocr_field_results` directly before publishing, so the consumer's
only job on `.completed` is `DocumentMetadataService.applyOcrFields` -- the
one write path (`document_metadata_values`) that only dms-core owns, and
which never overwrites a `source='manual'` value. `.failed` only logs;
`ocr_status`/`last_error` are already persisted.

Idempotent by construction (`applyOcrFields` is delete-then-insert per field
and override-safe), so a redelivered event converges to the same state with
no dedupe table. Staleness is handled separately via the `attempt` field
above.

Since Phase 2 the consumer also stamps
`document_versions.ocr_fields_applied_at` (V38) after applying. That marker
is what makes a *lost* `.completed` event detectable -- see below.

## Backstop for a lost `.completed`: `OcrFieldsReconciler`

A `.completed` event that never arrives leaves a document in a state no
part of the event path will ever revisit: `document_ocr_field_results`
correct, `document_metadata_values` silently empty. This is not
hypothetical -- the `OutboxRelay` double-encoding defect killed every
delivery for an entire phase (see `docs/ocr/phase1-verification.md`).

`OcrFieldsReconciler` (dms-core, `@Scheduled`, `dms.ocr.reconciler-*`) is
the backstop. Every minute it looks for versions where `ocr_status =
'completed'`, `ocr_fields_applied_at IS NULL`, the newest OCR job's
`finished_at` is older than `dms.ocr.stale-after-minutes` (10 by default,
far beyond the normal commit -> 2s relay poll -> listener latency), and at
least one applicable field result exists. For each, it binds the row's own
tenant, applies the fields, stamps the marker -- and **logs a warning**.

The warning is the deliverable. A healthy system produces none; each one
that appears names a message that genuinely went missing. That is the
difference from the poll-time re-apply it replaces
(`dms.ocr.poll-reapply-enabled`), which only ever rescued a document
somebody happened to open, and rescued it silently -- which is precisely
how a total failure of this path went unnoticed for a phase.

Concurrency-safe without a lock: the marker stamp is a guarded CAS
(`UPDATE ... WHERE ocr_fields_applied_at IS NULL`), so if two instances, or
the reconciler and the listener, reach the same version at once, exactly
one gets a row back and logs. Same approach as ocr-worker's reaper.

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

**Where `requires_review` actually lives, as of Phase 4b.** The flag is
still carried in the output JSON and the `.completed` payload as above,
but it is no longer *only* there: `document_versions.requires_review` and
`document_versions.review_reasons` (V41) are written by ocr-worker in the
same transaction as the rest of the outcome. The event payload is a
write-once record of a moment; the review queue needs queryable state, and
building it from artifacts in object storage would mean fetching every
document's JSON to filter in memory. Both are written unconditionally,
including the false case -- a re-run that fixes a document has to take it
out of the queue. `document_versions.page_count` (V42) was added the same
way and for the same reason: the review screen needs it before it fetches
a page, and a document that failed before its file could be opened has no
artifact to read it from.

**The review screen does NOT use `POST /ocr/rubber-band`.** The tradeoff
noted directly above -- an ad-hoc rubber-band pull overwriting the
full-document result -- is exactly why. Phase 4c added
`POST /ocr/rubber-band/preview` and `GET /ocr/page-image/{version_id}`,
which claim no job, write no row and publish no event. A reviewer
re-checking one number must not replace the document's entire field set
with that one crop's result. The reviewer's *decision* is what gets
persisted, by dms-core, into `document_ocr_corrections` (V43).
