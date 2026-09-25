# Phase 1 close-out

Companion to `docs/ocr/phase1-verification.md`, which met Phase 1's exit
criteria but carried five open items forward. This closes four of them,
with live runs rather than reasoning, and records the three defects those
runs found.

Harness: `scripts/dev/phase1_closeout_verification.py` (`fields`,
`reaper`, `timeout`). Raw per-run output:
`docs/ocr/phase1-closeout-{fields,reaper,timeout}.json`. Each subcommand
refuses to run unless the container it is checking is actually configured
for that check, so a pass cannot be produced by the wrong environment.

**Verdict: all three live checks pass.** Run 2026-09-25 against the full
local compose stack.

| open item | check | result |
|---|---|---|
| #3 fields end to end | `fields` | **PASS** — 7/7 requested fields extracted; `document_metadata_values` written by the event path with the poll-time re-apply disabled |
| #4 the reaper never ran | `reaper` | **PASS** — SIGKILLed pool worker, lease expired at 60s, requeued at 54s, completed on attempt 2 |
| #1 uniform 1310s timeout | `timeout` | **PASS** — `TIMEOUT` recorded from the document's real page count, one pool worker replaced, next job completed in 2s |
| #2 no `mem_limit` | — | **DONE** — `mem_limit: 8704m` set from the measured 6.89 GiB peak |
| #5 Docling drops table cells | — | still open, accuracy work for Phase 2/3 |

---

## 1. Fixes

### 1.1 `mem_limit` on ocr-worker

`infra/local/docker-compose.yml` now sets **`mem_limit: 8704m` (8.5 GiB)**
on `ocr-worker` — the measured **6.89 GiB peak** (368 `docker stats`
samples across the Phase 1 run, `OCR_WORKER_PROCESSES=2`; p95 5.77 GiB,
median 4.60 GiB) plus ~25% headroom. Confirmed applied:
`HostConfig.Memory=9126805504`.

Before this the container ran with `HostConfig.Memory=0` — nothing bounded
it at all, and it had already been OOM-killed once by the host during load
testing. The limit converts "the host runs out and the kernel picks a
victim" into "this one container is killed and restarted"
(`restart: unless-stopped` was already in place for exactly that).

Both numbers are documented in the ocr-worker README, together with the
scaling rule (~3.4 GiB per pool process plus a ~1.5 GiB shared floor), so
raising `OCR_WORKER_PROCESSES` doesn't silently outgrow the limit.

### 1.2 Prefetch tied to `OCR_WORKER_PROCESSES`

`PREFETCH_COUNT` now defaults to `OCR_WORKER_PROCESSES` rather than a flat
`4`. **The requested value, unchanged** — no substitution to explain.

The Phase 1 run confirmed the old behavior live: RabbitMQ held exactly 4
deliveries unacked throughout, against a pool of 2. Two of those were
messages this worker had no free process to start, held and invisible to
any other consumer for as long as the two running documents took — up to
~1000s for a 35-page scan in that run. Confirmed applied: both consumers
now log `prefetch=2`.

One caveat, documented rather than silently accepted: `ScanCompletedConsumer`
and `OcrRequestedConsumer` set QoS on separate channels, so the
process-wide ceiling is `2 × PREFETCH_COUNT`. Moot until scan-worker
exists, since nothing publishes `document.version.scan.completed` today.

### 1.3 Per-document timeout from the real page count

The budget is now `OCR_TIMEOUT_BASE_S + OCR_TIMEOUT_PER_PAGE_S × the
document's actual page count`, read the instant PyMuPDF has opened the
file. A one-page document gets **85s** at the defaults, not the 1310s
worst-case ceiling every document used to get.

Three layers (`backend/workers/ocr-worker/src/ocr_worker/deadline.py`):

1. **Soft** — checked between pages, raises `OcrTimeoutError`, rides the
   ordinary failure path, pool worker survives.
2. **Hard** — a watchdog thread firing `OCR_TIMEOUT_HARD_GRACE_S` after
   the budget. A wedged native PaddleOCR/Docling call never returns to a
   soft check and cannot be interrupted from Python, so the watchdog
   writes the `TIMEOUT` record itself and then `os._exit`s with a
   distinctive code. `pebble` replaces that one worker.
3. **The `pebble` ceiling** — sized above the largest budget layer 2 can
   arm; only fires if the watchdog thread never ran at all.

Layer 2 is why `TIMEOUT` is now recorded at all. Previously a timeout left
the row `processing` with a live lease and nothing written; the reaper
picked it up `OCR_LEASE_DURATION_S` (1800s) later and recorded
`WORKER_CRASH` — the wrong code, half an hour late.

The budget is armed **before** Docling's conversion, not after `load()`
returns. That ordering is load-bearing: the conversion is the single most
expensive step in the pipeline (~104s for a 35-page scanned PDF, measured
during this close-out), and arming afterwards would leave it outside the
budget entirely. The same hook fixed the `TOO_MANY_PAGES` guard, which
`errors.py` has always described as running "before any rendering/Docling
work" but which in fact ran after it — so an oversized document paid the
full conversion cost before being rejected, on every attempt.

---

## 2. Live verification

### 2.1 Fields end to end (open item #3) — PASS

`docs/ocr/phase1-closeout-fields.json`.

A real invoice uploaded through `POST /api/v1/documents` as document type
`INVOICE` for the ABC Company tenant (the one seeded type with a real
metadata schema — 11 fields, 6 mandatory), with
`requestedFields` naming all six mandatory fields plus
`purchase_order_number`.

**dms-core ran with `OCR_POLL_REAPPLY_ENABLED=false`** for this run, and
the check refuses to start otherwise. The harness also waits on the
*database*, never on `GET /ocr-status`, and only calls that endpoint after
all its evidence is already collected — so the polling path cannot have
contributed to the result.

| | |
|---|---|
| `ocr_status` | `completed`, 2.0s after upload |
| `document_ocr_field_results` | **7 rows, all `extracted`** |
| `document_metadata_values` | **7 rows, all `source='ocr'`**, 1.0s later |
| outbox | `.requested` published, `.completed` published |
| attempts | 1 |

| field | extracted value | confidence |
|---|---|---|
| `invoice_number` | `INV-2026-00184` | 0.80 |
| `invoice_date` | `2026-03-14` | 0.90 |
| `amount` | `2500.00` | 0.80 |
| `currency` | `GBP` | 0.85 |
| `vendor_supplier_name` | `Northwind Trading Ltd` | 0.80 |
| `classification_level` | `confidential` | 0.85 |
| `purchase_order_number` | `PO-55821` | 0.80 |

With the safety net off, the only thing that can have written
`document_metadata_values` is `OcrEventsConsumer` reacting to
`document.version.ocr.completed`. **Open item #3 is closed.**

### 2.2 Reaper (open item #4) — PASS

`docs/ocr/phase1-closeout-reaper.json`. Run with
`infra/local/docker-compose.closeout-reaper.yml` (lease 60s, reaper
interval 10s, backoff base 5s).

A scanned (image-only) invoice was uploaded, the pool worker actually
running it was identified by CPU-time delta and `SIGKILL`ed 5s in.

| | |
|---|---|
| killed | pool worker pid 8, 06:57:06 |
| lease expired, reaper requeued | **54s** after the kill |
| retry event emitted | after the 5s backoff elapsed |
| claimed again | attempt **2** |
| final state | `completed`, 32s after requeue |
| outbox | `.requested` ×2, `.completed` ×1, all published |

### 2.3 Timeout (open item #1) — PASS

`docs/ocr/phase1-closeout-timeout.json`. Run with
`infra/local/docker-compose.closeout-timeout.yml`
(`OCR_TIMEOUT_BASE_S=5`, `OCR_TIMEOUT_PER_PAGE_S=1`,
`OCR_TIMEOUT_HARD_GRACE_S=5`).

A one-page image-only PDF needs ~20s of real PaddleOCR against a 6s
budget.

| | |
|---|---|
| budget applied | **6s** = 5 + 1 page × 1s |
| the old uniform ceiling would have been | **55s** = 5 + `OCR_MAX_PAGES`(50) × 1s |
| hard deadline | 11s |
| terminal state | `failed` at 13.0s |
| `processing_jobs.last_error` | `TIMEOUT: exceeded the 6s budget for this document (5.0s + pages x 1.0s)` |
| outbox | `.failed` published |
| pool | one worker replaced, others survived, container `RestartCount=0` |
| next job | **completed in 2.0s** |

Worker log, end to end:

```
07:02:25 ERROR pipeline  Hard per-document timeout after 6s (+5s grace) for version d3fc1821-... -- recording TIMEOUT and terminating this pool worker
07:02:25 ERROR pipeline  OCR processing failed for version d3fc1821-...
07:02:25 ERROR consumer  Version d3fc1821-... timed out; its pool worker recorded TIMEOUT and was replaced
07:02:30 INFO  pipeline  Version 75834728-... resolved 7 requested field(s)      <- the next job
07:02:47 INFO  worker_pool  ocr-worker pool worker pid=3404 warmed up            <- the replacement
```

---

## 3. Defects found by these runs

All three were invisible to the existing test suite and to the Phase 1
exit-criteria run. All three are fixed, with regression cover.

### 3.1 The queue path never extracted requested fields at all

`docs/events/ocr-scanning-events.md` has said since Phase 1 that the
`.requested` event carries no resolved field specs *because* ocr-worker
re-reads `document_versions.requested_fields` and resolves the schema
itself at claim time ("Decision 1"). **That resolution was never
implemented.** Both queue consumers dispatched with
`requested_fields=None`, so the pipeline took the legacy generic-regex
branch, `upsert_field_results` was never reached, and
`document_ocr_field_results` was never written by a real upload. Only the
dev-trigger endpoint, which passes resolved specs in its request body,
ever exercised the selectable-field path.

Everything downstream inherited the hole: with no field-result rows,
`OcrEventsConsumer` had nothing to apply, and `document_metadata_values`
was never populated from OCR on the real upload path either.

Why Phase 1 missed it: its harness staged a throwaway document type with
no metadata schema, so an empty `document_ocr_field_results` looked like
the expected outcome rather than a symptom. That is exactly what open item
#3 flagged — the run just could not tell the two apart.

**Fix:** `OcrRepository.resolve_requested_field_specs` joins
`document_type_fields` (at the document type's *current* schema version) →
`metadata_fields` → `metadata_field_options`, mirroring
`DocumentTypeFieldServiceImpl#getSchema`'s filters. The consumers now pass
`resolve_fields_from_db=True`; the dev-trigger endpoint is unchanged. An
empty column still resolves to "no restriction", so a document uploaded
without `requestedFields` behaves exactly as before. Covered by
`tests/test_requested_field_resolution.py` (9 tests, real in-memory DB).

Live confirmation: `Version 8bd3a320-...: resolved 7 requested field(s)
from document_versions.requested_fields`.

### 3.2 The reaper's requeue silently dropped every retry

`reap_requeue` moved an expired-lease job back to `queued` with
`next_attempt_at = now + backoff` **and inserted the `.requested` outbox
event in the same transaction**. `OutboxRelay` polls every 2s, so that
event always reached the consumer *inside* the backoff window;
`claim_job`'s own `next_attempt_at <= now` guard then correctly refused the
claim, the consumer acked and skipped — and nothing ever published again.
The job sat in `queued` with a long-elapsed `next_attempt_at` forever,
because `find_expired_leases` only looks at `processing` rows, and its
version never left `pending`.

Caught on the first `reaper` run:

```
05:23:59  Reaper requeued job ec2e01e1-... attempts=1/5, backoff=5s
05:24:01  Version 9101d3cf-...: no claimable job; ack-and-skip
```

The larger the backoff, the more certain the drop — so the **production**
30s backoff was strictly worse than the 5s one this was found with. Every
reaper retry in the system was dead.

Its reach was visible the moment the fix shipped: restarting ocr-worker
immediately rescued **five** jobs stranded this way by earlier runs, all
logging `Reaper re-requested job ... now that its backoff has elapsed:
attempt 2/5`.

Why the tests missed it: `test_reaper.py` asserted that `reap_requeue`
wrote the row *and* emitted the event — the two halves that must not
happen together. It could not see the interaction with the relay's
latency or with `claim_job`'s own guard.

**Fix:** the requeue emits nothing; a later reaper tick finds the job via
`find_due_retries` once the backoff has actually elapsed and emits the
event then (`publish_due_retry`, which clears `next_attempt_at` in the same
CAS, so the event can never be emitted twice and the claim gate opens at
the same instant). Covered by four new tests in `test_reaper.py`, including
"a fresh upload is never picked up as a due retry" (dms-core's own rows
leave `next_attempt_at` NULL) and "claimable immediately once emitted".

The ack-and-skip log line was also misleading — it always said "already
owned by a live lease", which sent the first hour of this investigation
down the wrong path. It now reports what the row actually says
(`OcrRepository.describe_unclaimable`).

### 3.3 `OCR_LEASE_DURATION_S` must exceed the longest possible document

Provoked by the `reaper` overlay's deliberately short 60s lease, against
leftover 35-page scanned fixtures (~1000s each): the lease expired while
the worker was still legitimately working, the reaper requeued, the retry
restarted the same document from scratch, and it cycled until
`MAX_DELIVERY_ATTEMPTS`. The pool was saturated by documents that were
never actually failing.

That is a correct consequence of a deliberately broken setting, not a bug
in the reaper — but nothing warned about it, and diagnosing it from the
symptom is slow. ocr-worker now logs a warning at startup when
`OCR_LEASE_DURATION_S` is not greater than
`OCR_TIMEOUT_BASE_S + OCR_MAX_PAGES × OCR_TIMEOUT_PER_PAGE_S + 2 ×
OCR_TIMEOUT_HARD_GRACE_S`. The shipped defaults satisfy it (1370s against
a 1800s lease) and a test pins that.

### 3.4 (not a product defect) `DocumentUploadOcrQueueingIT` was racing the live worker

The IT asserted the freshly-uploaded job is `queued`. It runs against the
live compose stack, where a real ocr-worker consumes the queue it just
published to; the relay polls every 2s and the test takes ~3.5s, so on an
**idle** worker the row is legitimately already `processing`. It passed
during Phase 1 only because the pool happened to be saturated at the time.

Relaxed to accept `queued` or `processing` (and 0 or 1 attempts), with the
reasoning recorded in the test. What belongs to `uploadDocument` — exactly
one active job row plus the outbox event, in the same transaction as the
version — is still asserted exactly.

---

## 4. Test results

| suite | result |
|---|---|
| ocr-worker `pytest` | **177 passed**, 2 deselected (`-m model`) — was 145 |
| dms-core `mvn test` | **41 passed**, 0 failures |
| dms-core integration tests | **2 passed** (`DocumentUploadOcrQueueingIT`, `OcrEventsConsumerLiveIT`) |

The dms-core suites must run **on the compose network with `DB_URL` et al.
pointed at the service names** — `contextLoads` boots the real application
context and Flyway, so from an isolated container it fails on
`localhost:5433` for reasons that have nothing to do with the code under
test. Surefire still excludes `*IT.java` by default, so the two integration
tests need naming explicitly until the build wires up failsafe.

---

## 5. Recommendation: keep the poll-time re-apply through Phase 2

Open item #3 is closed and `dms.ocr.poll-reapply-enabled` makes removing
the re-apply a one-line change. **Keep it on through Phase 2 anyway.**

What the `fields` run actually proved is that the event path works for
**one document, uncontended, on a healthy stack**. That is exactly the
gap the item named, and it is now closed. It is not evidence about the
conditions the safety net exists for:

- **Under real concurrent load.** Untested. `OcrEventsConsumer` runs on a
  Spring AMQP listener thread with no `TenantContext` of its own; nothing
  has yet exercised many of them at once against the schema cache and
  `applyOcrFields`' delete-then-insert.
- **When a `.completed` event is lost.** Phase 1's run produced 0
  dead-letters on `dms-core.q.ocr-events`, but "hasn't happened" is not
  "can't". Without the re-apply, a single dropped event means a document
  whose `document_ocr_field_results` are correct and whose
  `document_metadata_values` are silently empty, with no path to recovery
  short of a manual OCR re-run.
- **Phase 2 will change this code.** Higher DPI, tiling, and accuracy work
  all touch the producer side. Removing a safety net at the start of the
  phase that will churn it most is the wrong order.

There is also a specific reason to be cautious here rather than in
general: this exact safety net already masked a total failure of the
event path for a whole phase (the `OutboxRelay` double-encoding defect).
The lesson from that is not "the net is bad" — it is "the net must not be
the only thing that ever proves the path works". A config flag plus a
harness that runs with it off is precisely that, and it is now in place.

**Concrete proposal:** remove the re-apply at the **end** of Phase 2,
gated on a concurrent run — the 50-upload `phase1_verification.py` harness
against a document type with a real metadata schema, with
`OCR_POLL_REAPPLY_ENABLED=false`, asserting every completed version has
its `document_metadata_values`. That is the "verified under real
concurrent load" bar the original comment set, and today's single-document
run does not meet it.

---

## 6. Still open after this

1. **Docling table extraction drops cells** on the 35-page native PDF
   (Phase 1 open item #5). Accuracy work, Phase 2/3.
2. **The zone / rubber-band endpoints get no per-document deadline.** They
   hold a transaction open across their inference, so a watchdog writing
   from another thread would block on their own row lock. They are
   single-page, synchronous and caller-visible, and remain bounded only by
   the outer `pebble` ceiling. Closing this properly means giving them the
   same claim/run/commit split `process_version` already has.
3. ~~**A `processing_jobs` row with a NULL `lease_expires_at` is
   unreapable.**~~ **CLOSED.** `find_expired_leases` filtered on
   `lease_expires_at < now`, which is NULL (not true) for a leaseless row —
   so it was invisible to the reaper forever and kept holding V36's
   partial unique index slot for its version.

   The single writer was `OcrEventsConsumerLiveIT`, which hand-built a
   `processing` row to exercise the staleness check and set neither
   `startedAt` nor `leaseExpiresAt`; ocr-worker's own `claim_job` — the
   only production writer of that status — has always set both in one
   UPDATE. Four such rows had accumulated in the local database. Fixed in
   four places, deliberately overlapping:

   - **The writer.** The IT now sets both columns, and deletes its job row
     in an `@AfterEach` (it runs against the shared compose database, so
     an active row it leaves behind accumulates across runs).
   - **The schema.** `V37__processing_jobs_require_lease.sql` adds
     `CHECK (status <> 'processing' OR lease_expires_at IS NOT NULL)`, so
     the shape cannot be written again by anything. Applied to the local
     database; the four leftovers were moved to `dead_letter` first (kept
     as history, not deleted), since `ADD CONSTRAINT` validates existing
     rows.
   - **The reaper.** `OcrRepository._reapable` now also matches a
     leaseless `processing` row older than `ocr_lease_duration_s`, by
     `COALESCE(started_at, created_at)` — all four leftovers had
     `started_at` NULL too, so `started_at` alone would have left them
     just as invisible; `created_at` is NOT NULL with a DB default. Such a
     row is reaped with a distinct `WARNING` naming it, because nothing
     should be producing one.
   - **The CAS updates.** `reap_requeue`/`reap_dead_letter` share that one
     predicate with the scan. Had they kept the narrower
     `lease_expires_at < now`, the scan would have returned these rows on
     every tick while the UPDATEs matched nothing — a silent spin instead
     of a fix.

   Covered by 6 new tests in `tests/test_reaper.py`.
4. **A retry event that dead-letters strands its job.** `publish_due_retry`
   clears `next_attempt_at` when it emits, so if that one delivery is lost
   the job stays `queued` with nothing to re-trigger it. Much narrower than
   3.2 (one lost message, not every retry), but the same shape.
