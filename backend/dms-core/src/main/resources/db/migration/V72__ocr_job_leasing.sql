-- OCR job leasing (Phase 1 stability): replaces the synchronous
-- HTTP-triggered OCR call with a claim/lease model driven by
-- document.version.ocr.requested/.completed/.failed outbox events, so a
-- worker crash or an abandoned client connection can never leave a version
-- stuck in 'processing' forever -- see docs/ocr/failure-analysis.md #1/#3
-- for the real, live-measured race and long-held-transaction evidence this
-- fixes, and docs/ocr/ocr-production-prompt.md section 3.1.
--
-- lease_expires_at: set by ocr-worker's claim UPDATE (now() + a configured
-- lease duration) when it starts processing a job; a background reaper
-- (also ocr-worker's own, since it already owns direct writes to
-- processing_jobs -- see V33's own comment) requeues or dead-letters any
-- row whose lease has expired without a matching completion.
--
-- next_attempt_at: backoff gate for the reaper's requeue path, so a
-- repeatedly-failing job doesn't get re-claimed immediately in a tight
-- loop.
ALTER TABLE processing_jobs
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

-- At most one ACTIVE ocr job per (reference_type, reference_id) at a time.
-- Deliberately a PARTIAL index (only queued/processing rows) rather than a
-- plain unique constraint: a version's job history accumulates one row per
-- attempt (upload, each re-run) exactly like today's un-indexed inserts
-- already do, but only ONE of those rows may ever be active at once. This
-- is what lets both the claim UPDATE (compare-and-swap on
-- reference_id+status) and a re-run (which inserts a fresh queued row once
-- the previous one has moved to completed/failed/dead_letter, landing
-- outside this index's predicate) rely on a simple WHERE clause instead of
-- a separate locking scheme.
CREATE UNIQUE INDEX uq_processing_jobs_active_ocr_ref
    ON processing_jobs (reference_type, reference_id)
    WHERE job_type = 'ocr' AND status IN ('queued', 'processing');

-- Reaper scan: only rows actually eligible for reaping.
CREATE INDEX ix_processing_jobs_lease_reaper
    ON processing_jobs (status, lease_expires_at)
    WHERE status = 'processing';
