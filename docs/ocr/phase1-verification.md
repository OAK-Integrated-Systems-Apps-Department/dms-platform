# Phase 1 (Stability) — exit-criteria verification

Companion to `docs/ocr/failure-analysis.md` (section 1, the *before* state)
and `docs/ocr/ocr-production-prompt.md` section 6 phase 1, whose exit
criteria this report answers:

> 50 mixed uploads including 10 concurrent produce zero crashes, zero stuck
> `processing` versions, and a clear status for every file.

**Verdict: met.** Run `phase1run`, 2026-09-25, against the full local
compose stack. Raw per-file results: `docs/ocr/phase1-run.json`.
Harness: `scripts/dev/phase1_verification.py`.

Two real defects were found by this run and fixed before it; they are the
substance of this report, because the exit criteria could not be met with
them present. See "Defects found" below.

## How the run was driven

`scripts/dev/repro_batch.py` (section 1's harness) drove the synchronous
`POST /ocr/process/{id}` dev-trigger endpoint, which is no longer the
production path. `phase1_verification.py` drives the real one: it stages
50 versions in Postgres + MinIO, then inserts
`document.version.ocr.requested` rows into `outbox_events` so **dms-core's
own `OutboxRelay` publishes them**, byte for byte the wire path
`DocumentServiceImpl.uploadDocument` produces. It then waits on the
database for each version's terminal state.

That choice matters: an earlier revision of the harness published straight
to RabbitMQ with pika, and passed — while the relay was broken and every
real upload was silently failing to trigger OCR at all. Publishing directly
exercises only half the path. `--trigger direct` still exists for
isolating ocr-worker, but it is not the default.

- 50 uploads cycling a 20-file mixed fixture set (`scripts/dev/build_fixtures.py`):
  native PDF, image-only PDF, PNG scan, two phone-photo JPEGs, HEIC,
  multi-page TIFF, CCITT G4 fax TIFF, three rotations, rotated native PDF,
  35-page native and 35-page scanned PDFs, password-protected PDF, four
  flavours of corrupt PDF, DOCX.
- 40 published at 1s intervals, then a final **10-event simultaneous
  burst** (published in 0.04s).
- Four items deliberately declare the wrong `Content-Type`
  (`application/octet-stream` for JPEG and HEIC — what Chrome really sends
  for `.heic` — plus the `image/x-png` and `image/tif` legacy aliases), so
  magic-byte sniffing is exercised on the live path, not only in unit tests.

## Results

| metric | result |
|---|---|
| uploads | 50 |
| terminal states reached | 50 / 50 |
| `completed` | 46 |
| `failed` (with an error code) | 4 |
| **stuck in `processing`** | **0** |
| **container crashes / restarts** | **0** (`RestartCount=0`, `OOMKilled=false` on both `oakdms-ocr-worker` and `oakdms-core`) |
| jobs needing a retry | 0 — every `processing_jobs` row finished at `attempts=1` |
| reaper requeues / dead-letters | 0 (no lease ever expired) |
| `outbox_events` not published | 0 — 53 `.requested`, 49 `.completed`, 4 `.failed`, all `published` |
| `dms-core.q.ocr-events.dead-letter` | 0 |
| `ocr-worker.q.dead-letter` | 4 — exactly the 4 failed jobs, which is the designed path (the pipeline commits the failure, then nacks without requeue) |
| wall clock | 1925s |

### Failures, all with a clear code

| file | code | message |
|---|---|---|
| `password_protected.pdf` (×2) | `ENCRYPTED_PDF` | `password_protected.pdf is password-protected` |
| `corrupt_tiny_fragment.pdf` (×2) | `CORRUPT_FILE` | `corrupt_tiny_fragment.pdf: Failed to open file` |

No stack traces reached `processing_jobs.last_error`; every failure is
`CODE: human message` per the taxonomy in `errors.py`.

The other three corrupt fixtures — `corrupt_truncated.pdf`,
`corrupt_bad_header.pdf`, `corrupt_mutated.pdf` — **completed**, in 2–4s.
PyMuPDF's repair path recovered them (the intended behavior: try repair
before declaring `CORRUPT_FILE`). MuPDF logs its own `format error: object
is not a stream` / `syntax error: expected object number` diagnostics to
stderr while doing so; those are not failures. Whether the recovered text
is *correct* is an accuracy question for Phase 2, not a stability one.

### Concurrency behavior

Throughout the run RabbitMQ held exactly `prefetch_count=4` deliveries
unacked with the remainder queued (observed repeatedly: `14 ready / 4
unacked`, `22 ready / 4 unacked`). The 10-event burst queued rather than
fanning out. That is the fix working: bounded concurrency, with the queue
absorbing the spike instead of the worker.

### Latency (single-attempt, seconds)

| fixture class | observed |
|---|---|
| native-text PDF | 1.3 – 2.3 |
| DOCX | 0.2 – 0.9 |
| single-page scan / photo / HEIC | 18.5 – 40.1 |
| multi-page TIFF (incl. G4) | 61.5 – 78.7 |
| rotated native-text PDF | 145.0 – 215.1 |
| 35-page native PDF | 91.1 – 93.0 |
| 35-page scanned PDF | 969.5 – 1014.6 |

The 35-page scanned PDF at ~1000s is the worst case. It sits inside the
task timeout, which `compute_task_timeout_s` deliberately computes as
`OCR_TIMEOUT_BASE_S + OCR_MAX_PAGES × OCR_TIMEOUT_PER_PAGE_S`
= 60 + 50×25 = **1310s** — a single conservative ceiling applied uniformly,
because the real page count isn't known until after the document is opened
in the pool worker, i.e. after dispatch. See "Open items" for the cost of
that choice.

### Memory — a real number, not a guess

`docker stats` sampled every 3s, 368 samples across the run, with
`OCR_WORKER_PROCESSES=2`:

| min | median | p95 | **max** |
|---|---|---|---|
| 3.04 GiB | 4.60 GiB | 5.77 GiB | **6.89 GiB** |

`oakdms-ocr-worker` currently has **no `mem_limit` in
`infra/local/docker-compose.yml`** (`HostConfig.Memory=0`), so nothing
bounds this. 6.89 GiB peak with only two pool processes is the measurement
the plan asked for before setting a limit; see "Open items".

## Defects found

Both were found by running the real path, and both were invisible to the
existing test suite. Both are fixed, with regression cover.

### 1. `OutboxRelay` double-encoded every event payload

`outbox_events.payload` is a `jsonb` column — already serialized JSON. The
relay passed that `String` to
`RabbitTemplate.convertAndSend`, whose `Jackson2JsonMessageConverter`
serialized it *again* into a quoted string literal
(`"{\"tenant_id\": ...}"`, header `__TypeId__=java.lang.String`). Both
directions of the Phase 1 queue path were dead:

- **dms-core → ocr-worker.** `json.loads` returned a `str`;
  `body.get(...)` raised `AttributeError` out of the consumer callback, so
  the delivery was neither acked nor nacked. RabbitMQ redelivered it on
  every reconnect, forever, each one permanently holding one of the four
  prefetch slots. Three such messages were found still stuck after a full
  stack restart. **Uploads through the real API never triggered OCR.**
- **ocr-worker → dms-core.** `OcrEventsConsumer.onMessage(Map<String,
  Object>)` got a `String`: `MismatchedInputException: Cannot construct
  instance of java.util.LinkedHashMap ... from String value`, retries
  exhausted, dead-lettered. `applyOcrFields` never ran off the event.

The second one was masked in the UI: `DocumentServiceImpl.getOcrStatus`
still re-applies OCR fields at poll time. That safety net is why nobody
noticed, and it is a concrete argument for keeping it — its own comment
says it stays "until it's verified under real concurrent load", and that
verification has not yet exercised a document type with a real metadata
schema.

Why the tests missed it: `DocumentUploadOcrQueueingIT` asserts the outbox
*row* is written and stops before the relay; `OcrEventsConsumerLiveIT`
publishes a `Map` by hand, which the converter encodes correctly. Neither
ever looked at the bytes the relay puts on the wire.

**Fix:** `OutboxRelay.jsonMessage` builds the `Message` itself — raw UTF-8
payload bytes, `content_type: application/json`, persistent, no
`__TypeId__` header (Spring AMQP's `TypePrecedence.INFERRED` then lets each
listener's own parameter type govern) — and sends it with
`rabbitTemplate.send`. Covered by `OutboxRelayWireFormatTest`, including a
test that pins the old `convertAndSend(String)` behavior so nobody
"simplifies" it back.

### 2. A poison message could wedge a prefetch slot forever

The `AttributeError` above was only able to stick because an exception
escaping the consumer callback leaves the delivery unsettled. ocr-worker's
consumers now decode and validate through shared helpers where **every
path ends in an explicit ack or nack**: a body that is not a JSON object, a
missing or unparseable `version_id`, and an unparseable `correlation_id`
all nack without requeue, straight to the dead-letter queue, with a log
line naming the reason.

Confirmed live: on restart with the fix in place, the three permanently
stuck messages were dead-lettered immediately —
`Discarding message on ocr-worker.q.ocr-requested whose JSON payload is a
str, not an object` ×3 — and the prefetch slots freed.

Covered by `tests/test_consumer_poison_messages.py` (20 tests, both
consumers), which also pins the two cases that must *not* dead-letter: a
non-`clean` `scan_status` is acked and skipped, even when its ids are
malformed.

## Test results

| suite | result |
|---|---|
| ocr-worker `pytest` | 145 passed, 2 deselected (`-m model`) — was 125 before this phase's 20 new consumer tests |
| dms-core `mvn test` | 41 passed, 0 failures (incl. `OutboxRelayWireFormatTest` 6/6) |
| dms-core integration tests | 2 passed (`DocumentUploadOcrQueueingIT`, `OcrEventsConsumerLiveIT`) |
| frontend `vitest` | 20 passed (2 files) |

Note for whoever runs these next: surefire's default excludes `*IT.java`,
so `mvn test` alone silently skips both integration tests. They have to be
named explicitly (`-Dtest='DocumentUploadOcrQueueingIT,OcrEventsConsumerLiveIT'`)
until the build wires up failsafe. Both need the local compose stack up and
must run on its network.

## Open items carried into Phase 2

> **Status: items 1-4 are closed.** See `docs/ocr/phase1-closeout.md` for
> the fixes and the live runs that verify them — and for the three defects
> those runs found, including two that made whole paths dead in production
> (the queue path never extracted requested fields at all; every reaper
> retry was silently dropped). Item 5 remains open as accuracy work.


1. **The timeout is one uniform 1310s ceiling, not a per-document budget.**
   `compute_task_timeout_s` sizes it from `OCR_MAX_PAGES`, so a one-page
   photo that hangs occupies a pool worker for ~22 minutes before pebble
   kills it — with `OCR_WORKER_PROCESSES=2`, that is half the capacity. The
   tradeoff is deliberate and documented (page count isn't known until
   after dispatch), but the plan asked for a per-page *and* per-document
   timeout. Closing the gap means either a cheap pre-dispatch page count or
   a timeout the worker re-arms once it knows the real count. Separately,
   the timeout path itself has unit coverage only — it never fired in this
   run, so its "pebble terminates and respawns, reaper reclaims the lease"
   behavior is verified by design and by `test_worker_pool.py`, not by a
   live run.
2. **No `mem_limit` on `ocr-worker`.** Now that peak RSS is measured at
   6.89 GiB (p95 5.77 GiB) with `OCR_WORKER_PROCESSES=2`, set one with
   headroom and re-run this harness to confirm the limit is not hit.
3. **Nothing exercised `applyOcrFields` end to end.** The harness's
   document type has no metadata schema, so `document_ocr_field_results`
   was empty and the `.completed` consumer had nothing to apply. The
   relay fix is proven (zero dead-letters, all events `published`), but the
   *effect* of the `.completed` event on `document_metadata_values` has
   only ever been proven by `OcrEventsConsumerLiveIT`'s hand-published
   `Map`. Until a run covers it, `getOcrStatus`'s poll-time re-apply stays.
4. **The reaper never ran.** No lease expired in this run, so the requeue
   and dead-letter paths have unit coverage (`test_reaper.py`) but no live
   exercise. A deliberate fault-injection run (kill a pool worker
   mid-document) is needed before claiming "no version stays `processing`
   indefinitely" is verified rather than designed.
5. **Docling table extraction is dropping cells** on the 35-page native
   PDF: `24 of 24 pdf cells matched neither a row nor a column band of the
   378x2 grid and were dropped from the table`. Accuracy work, Phase 2/3.
