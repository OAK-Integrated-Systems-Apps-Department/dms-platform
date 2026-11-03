-- 6.1 Records Retention & Compliance Management (CRM-FR-001 .. 018).
--
-- A document's LIFECYCLE is separate from documents.status (which is the approval/workflow status):
--
--   active    the normal state; listed everywhere
--   inactive  switched off by hand (CRM-FR-002); hidden from standard listings and search (CRM-FR-004)
--   archived  moved there by its retention policy (CRM-FR-007); hidden the same way
--
-- Inactive and archived documents stay fully accessible to people allowed to see the Archive view (CRM-FR-005/009).
-- A document under legal hold (V127: documents.legal_hold) is never changed by either route (CRM-FR-014/015).
--
-- Everything about HOW long and WHEN is data an administrator edits, not code:
--
-- retention_policies          one rule per scope: the whole organisation (the default), a document type, a department
--                             or a folder. Active duration + archive duration (CRM-FR-006/008), each in days, months
--                             or years, counted from the document creation or last change. framework is a free
--                             label (GDPR, HMRC, Companies Act 2006 ... or anything) - the rule IS the configuration
--                             (CRM-FR-010, CRM-NFR-006).
-- tenant_retention_settings   per organisation: whether the automatic job runs, which rule wins when several apply
--                             (CRM alternate flow), what happens to a document no rule covers, and whether a reason
--                             is mandatory for a manual status change. No row = the defaults below.

ALTER TABLE documents
    ADD COLUMN lifecycle_state          VARCHAR(20) NOT NULL DEFAULT 'active',
    ADD COLUMN lifecycle_changed_at     TIMESTAMPTZ,
    ADD COLUMN lifecycle_changed_by     UUID,
    ADD COLUMN lifecycle_change_source  VARCHAR(20),
    ADD COLUMN lifecycle_reason         TEXT,
    ADD COLUMN reactivated_at           TIMESTAMPTZ,
    ADD COLUMN retention_policy_id      UUID,
    ADD COLUMN archive_due_at           TIMESTAMPTZ,
    ADD COLUMN review_due_at            TIMESTAMPTZ,
    ADD COLUMN review_flagged_at        TIMESTAMPTZ,
    ADD COLUMN retention_extended_until TIMESTAMPTZ,
    ADD COLUMN no_policy_flagged_at     TIMESTAMPTZ,
    ADD CONSTRAINT ck_documents_lifecycle_state CHECK (lifecycle_state IN ('active', 'inactive', 'archived')),
    ADD CONSTRAINT ck_documents_lifecycle_source
        CHECK (lifecycle_change_source IS NULL OR lifecycle_change_source IN ('manual', 'retention', 'legal_hold'));

CREATE INDEX idx_documents_lifecycle ON documents (tenant_id, lifecycle_state) WHERE NOT is_deleted;
CREATE INDEX idx_documents_review_flagged ON documents (tenant_id, review_flagged_at)
    WHERE review_flagged_at IS NOT NULL AND NOT is_deleted;

CREATE TABLE retention_policies (
    policy_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL REFERENCES tenants (tenant_id),
    name               VARCHAR(200) NOT NULL,
    description        TEXT,
    scope_type         VARCHAR(20)  NOT NULL,
    scope_id           UUID,
    include_subfolders BOOLEAN      NOT NULL DEFAULT true,
    active_amount      INTEGER      NOT NULL,
    active_unit        VARCHAR(10)  NOT NULL,
    archive_amount     INTEGER      NOT NULL DEFAULT 0,
    archive_unit       VARCHAR(10)  NOT NULL DEFAULT 'years',
    clock_start        VARCHAR(20)  NOT NULL DEFAULT 'created',
    framework          VARCHAR(120),
    enabled            BOOLEAN      NOT NULL DEFAULT true,
    created_by         UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by         UUID,
    updated_at         TIMESTAMPTZ,
    is_deleted         BOOLEAN      NOT NULL DEFAULT false,
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT ck_retention_policies_scope_type CHECK (scope_type IN ('organisation', 'document_type', 'department', 'folder')),
    CONSTRAINT ck_retention_policies_scope CHECK ((scope_type = 'organisation') = (scope_id IS NULL)),
    CONSTRAINT ck_retention_policies_active_amount CHECK (active_amount > 0),
    CONSTRAINT ck_retention_policies_archive_amount CHECK (archive_amount >= 0),
    CONSTRAINT ck_retention_policies_active_unit CHECK (active_unit IN ('days', 'months', 'years')),
    CONSTRAINT ck_retention_policies_archive_unit CHECK (archive_unit IN ('days', 'months', 'years')),
    CONSTRAINT ck_retention_policies_clock CHECK (clock_start IN ('created', 'last_modified'))
);

-- One rule per scope, so "which rule covers this" is never two rows of the same kind.
CREATE UNIQUE INDEX uq_retention_policies_scope
    ON retention_policies (tenant_id, scope_type, COALESCE(scope_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE NOT is_deleted;
CREATE INDEX idx_retention_policies_tenant ON retention_policies (tenant_id) WHERE NOT is_deleted;

CREATE TABLE tenant_retention_settings (
    tenant_id             UUID PRIMARY KEY REFERENCES tenants (tenant_id) ON DELETE CASCADE,
    automation_enabled    BOOLEAN     NOT NULL DEFAULT true,
    precedence            VARCHAR(20) NOT NULL DEFAULT 'longest',
    when_no_policy        VARCHAR(20) NOT NULL DEFAULT 'flag',
    require_status_reason BOOLEAN     NOT NULL DEFAULT false,
    updated_by            UUID,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_tenant_retention_precedence CHECK (precedence IN ('longest', 'most_specific')),
    CONSTRAINT ck_tenant_retention_no_policy CHECK (when_no_policy IN ('flag', 'ignore'))
);

-- New permission codes (granted to no role; TENANT_ADMIN / PLATFORM_ADMIN hold them implicitly, as every permission).
--   crm.view-archive                       see the Inactive / Archive view
--   crm.manage-retention-policies.view     read the retention rules
--   crm.review-retention                   review documents past their total retention: extend or dispose
--   crm.configure-retention                the settings, and running the retention job now
INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, v.action_code, v.action_name
FROM (VALUES
    ('VIEW_ARCHIVE', 'View Inactive and Archived Documents'),
    ('REVIEW_RETENTION', 'Review Expired Documents'),
    ('CONFIGURE_RETENTION', 'Configure Retention Settings')
) AS v(action_code, action_name)
JOIN permission_modules pm ON pm.module_code = 'CRM'
ON CONFLICT (module_id, action_code) DO NOTHING;

INSERT INTO permissions (permission_code, name, action_id)
SELECT v.permission_code, v.name, pa.action_id
FROM (VALUES
    ('crm.view-archive', 'View inactive and archived documents', 'VIEW_ARCHIVE'),
    ('crm.manage-retention-policies.view', 'View retention policies', 'MANAGE_RETENTION_POLICIES'),
    ('crm.review-retention', 'Review documents past their retention period', 'REVIEW_RETENTION'),
    ('crm.configure-retention', 'Configure retention settings', 'CONFIGURE_RETENTION')
) AS v(permission_code, name, action_code)
JOIN permission_modules pm ON pm.module_code = 'CRM'
JOIN permission_actions pa ON pa.module_id = pm.module_id AND pa.action_code = v.action_code
ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING;
