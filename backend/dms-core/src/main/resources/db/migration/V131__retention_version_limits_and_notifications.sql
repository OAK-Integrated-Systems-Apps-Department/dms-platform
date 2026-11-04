-- 6.1 follow-ups: old-version retention driven by the same rules, and notifications for retention events.
--
-- Version limits (per retention rule, both optional - a rule may use one, both or neither):
--   max_versions        keep at most this many versions of a document
--   version_age_*       remove superseded versions older than this
-- A version outside either limit is REMOVED = hidden from the version history (is_deleted, as the recycle bin does)
-- and marked retention_removed_at. It stays restorable for tenant_retention_settings.version_grace_days; after that
-- its files are destroyed for good (purged_at). A version is never removed while it is the current, latest or
-- published one, protected (dvc.protect-versions), signed, part of an approval, linked from a relationship, or while
-- the document is under legal hold - and an administrator can restore one and so exempt it (retention_keep).

ALTER TABLE retention_policies
    ADD COLUMN max_versions       INTEGER,
    ADD COLUMN version_age_amount INTEGER,
    ADD COLUMN version_age_unit   VARCHAR(10),
    ADD CONSTRAINT ck_retention_policies_max_versions CHECK (max_versions IS NULL OR max_versions >= 1),
    ADD CONSTRAINT ck_retention_policies_version_age
        CHECK ((version_age_amount IS NULL AND version_age_unit IS NULL)
            OR (version_age_amount >= 1 AND version_age_unit IN ('days', 'months', 'years')));

ALTER TABLE tenant_retention_settings
    ADD COLUMN version_grace_days INTEGER NOT NULL DEFAULT 30,
    ADD CONSTRAINT ck_tenant_retention_grace CHECK (version_grace_days BETWEEN 0 AND 3650);

ALTER TABLE document_versions
    ADD COLUMN retention_removed_at TIMESTAMPTZ,
    ADD COLUMN retention_keep       BOOLEAN NOT NULL DEFAULT false;

-- The sweep that destroys removed versions once their grace period ends.
CREATE INDEX idx_document_versions_retention_removed ON document_versions (tenant_id, retention_removed_at)
    WHERE retention_removed_at IS NOT NULL AND purged_at IS NULL;

-- Notification categories (global rows, like V109 / V125). Standard: in-app always, email on unless the person turns it off.
INSERT INTO notification_categories (category_code, module, tier, default_email_enabled, display_name, description) VALUES
 ('retention.review.due', 'documents', 'standard', true, 'Documents waiting for a retention review',
  'Documents have reached the end of their retention period and need an administrator''s decision.'),
 ('retention.no-rule', 'documents', 'standard', false, 'Documents without a retention rule',
  'Documents were found that no retention rule covers.'),
 ('retention.archived.owner', 'documents', 'optional', false, 'Your documents were archived',
  'Documents you own were moved to the archive by a retention rule.')
ON CONFLICT (category_code) DO NOTHING;
