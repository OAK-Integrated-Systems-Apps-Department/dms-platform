-- 2.10 Check-in / Check-out (module CICO), exclusive-lock mode, plus the minimal document-level
-- Legal Hold flag that CICO-FR-009 needs something to check against.
--
-- Both live as columns on documents rather than separate tables: a document has at most ONE
-- lock and ONE hold at a time, every edit path already loads the document row, and the full
-- history (who/when/why, every check-out, check-in, force-release, hold placed/lifted) is the
-- audit trail - these columns are only the current state.
--
--   checked_out_by / checked_out_at / checkout_expires_at   set together or not at all (CHECK).
--       checkout_expires_at is the idle timeout: past it the lock no longer blocks anyone and the
--       next check-out simply takes over (audited as an auto-release). A crashed session can
--       therefore never leave a document stuck (CICO-NFR-004) - there is no state in which the
--       lock is "ambiguous", only held (and unexpired) or free.
--   legal_hold, legal_hold_at/by/reason   a held document refuses check-out and every edit path.
--       Placing/lifting use the already-seeded crm.place-legal-hold / crm.lift-legal-hold.
--
-- The CICO permissions (check-out, check-in, discard-checkout, force-release-lock) were seeded
-- by V10, so nothing to seed here.

ALTER TABLE documents
    ADD COLUMN checked_out_by UUID REFERENCES users (user_id),
    ADD COLUMN checked_out_at TIMESTAMPTZ,
    ADD COLUMN checkout_expires_at TIMESTAMPTZ,
    ADD COLUMN legal_hold BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN legal_hold_at TIMESTAMPTZ,
    ADD COLUMN legal_hold_by UUID REFERENCES users (user_id),
    ADD COLUMN legal_hold_reason VARCHAR(2000),
    ADD CONSTRAINT ck_documents_checkout_all_or_none CHECK (
        (checked_out_by IS NULL) = (checked_out_at IS NULL)
        AND (checked_out_by IS NULL) = (checkout_expires_at IS NULL)),
    ADD CONSTRAINT ck_documents_legal_hold_consistent CHECK (
        legal_hold = (legal_hold_at IS NOT NULL)
        AND (legal_hold OR (legal_hold_by IS NULL AND legal_hold_reason IS NULL)));

-- "What do I have checked out?" and the expiry sweep both read only the locked rows.
CREATE INDEX idx_documents_checked_out
    ON documents (tenant_id, checked_out_by)
    WHERE checked_out_by IS NOT NULL;
