-- 6.1 follow-up: the retention job no longer walks every document every hour.
--
-- Most runs only look at documents that are DUE (an archive or review date has passed) or have never been evaluated. A FULL
-- pass - every live document, recomputing its rule - still happens when an administrator changes a rule or a setting, on
-- "Apply rules now", and at least once a day (so a document that moved folder, changed type, or got new versions is picked
-- up). last_full_pass_at remembers when the last full pass finished, per organisation.

ALTER TABLE tenant_retention_settings
    ADD COLUMN last_full_pass_at TIMESTAMPTZ;

-- The "due soon" lookups of the quick pass.
CREATE INDEX idx_documents_archive_due ON documents (tenant_id, archive_due_at)
    WHERE NOT is_deleted AND lifecycle_state <> 'archived' AND NOT legal_hold;
CREATE INDEX idx_documents_review_due ON documents (tenant_id, review_due_at)
    WHERE NOT is_deleted AND lifecycle_state = 'archived' AND review_flagged_at IS NULL AND NOT legal_hold;
CREATE INDEX idx_documents_never_evaluated ON documents (tenant_id)
    WHERE NOT is_deleted AND archive_due_at IS NULL AND no_policy_flagged_at IS NULL AND NOT legal_hold
      AND lifecycle_state <> 'archived';
