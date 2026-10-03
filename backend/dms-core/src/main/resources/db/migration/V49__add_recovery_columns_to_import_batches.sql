-- Module 2.1 - File Import/Export: recovery of abandoned and stuck batches.
--
-- ImportBatchRecoveryJob cancels a batch left uploading with no activity
-- for too long, and recovers a queued/running batch that has stalled.
--
-- cancel_reason says who cancelled a batch: 'user' (the cancel endpoint)
-- or 'expired' (the job, after a period with no uploads). NULL for every
-- batch that isn't cancelled - and for batches cancelled before this
-- migration, which were all cancelled by their user and are read that way.
--
-- recovery_attempts counts the job's attempts on a stalled batch: the
-- first re-publishes its import_batch.submitted event, a later one fails
-- the rows that never got processed and finalises the batch.
--
-- Conventions mirrored from V44/V47: one ALTER TABLE per change, the CHECK
-- constraint named ck_import_batches_*.

ALTER TABLE import_batches
    ADD COLUMN cancel_reason VARCHAR(30) NULL,
    ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0;

ALTER TABLE import_batches
    ADD CONSTRAINT ck_import_batches_cancel_reason CHECK (cancel_reason IN ('user', 'expired'));
