-- 2.8 Document Relationships Management (DRM).
--
--   relationship_types      per-tenant, admin-editable link types (V107 seeds the 10 system types)
--   document_relationships  one row per linked pair; the inverse view is derived when reading
--
-- See docs/relationship-plan.md. Points worth knowing:
--   * One row per pair (D10). A symmetric type is stored with the lower
--     document id first (the service canonicalises), so the unique index
--     below also rejects the reversed duplicate.
--   * Removing a link keeps the row (removed_at set) so the history survives;
--     the unique and lookup indexes only cover active rows.
--   * source_version_id / target_version_id are nullable version pins for the
--     automatic Supersedes on publish (deferred, D2) - nothing writes them yet.
--   * No FK from the audit trail to this table: audit_events keeps what a
--     purged document was related to.

CREATE TABLE relationship_types (
    relationship_type_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants (tenant_id),
    code                 VARCHAR(60) NOT NULL,
    category             VARCHAR(40) NOT NULL,
    label                VARCHAR(120) NOT NULL,
    inverse_label        VARCHAR(120) NOT NULL,
    directionality       VARCHAR(12) NOT NULL,
    prevents_cycles      BOOLEAN NOT NULL DEFAULT false,
    is_system            BOOLEAN NOT NULL DEFAULT false,
    is_enabled           BOOLEAN NOT NULL DEFAULT true,
    display_order        INT NOT NULL DEFAULT 0,
    created_by           UUID REFERENCES users (user_id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by           UUID REFERENCES users (user_id),
    updated_at           TIMESTAMPTZ,
    is_deleted           BOOLEAN NOT NULL DEFAULT false,
    deleted_at           TIMESTAMPTZ,
    CONSTRAINT uq_relationship_types_tenant_type UNIQUE (tenant_id, relationship_type_id),
    CONSTRAINT ck_relationship_types_directionality CHECK (directionality IN ('directional', 'symmetric')),
    CONSTRAINT ck_relationship_types_symmetric_cycles CHECK (NOT (directionality = 'symmetric' AND prevents_cycles)),
    CONSTRAINT ck_relationship_types_system_not_deleted CHECK (NOT (is_system AND is_deleted))
);

CREATE UNIQUE INDEX uq_relationship_types_tenant_code
    ON relationship_types (tenant_id, code) WHERE is_deleted = false;
CREATE INDEX ix_relationship_types_tenant_order
    ON relationship_types (tenant_id, display_order) WHERE is_deleted = false;

CREATE TABLE document_relationships (
    relationship_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants (tenant_id),
    relationship_type_id UUID NOT NULL,
    source_document_id   UUID NOT NULL,
    target_document_id   UUID NOT NULL,
    source_version_id    UUID REFERENCES document_versions (version_id),
    target_version_id    UUID REFERENCES document_versions (version_id),
    origin               VARCHAR(24) NOT NULL DEFAULT 'manual',
    batch_id             UUID,
    note                 VARCHAR(1000),
    created_by           UUID REFERENCES users (user_id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by           UUID REFERENCES users (user_id),
    updated_at           TIMESTAMPTZ,
    removed_at           TIMESTAMPTZ,
    removed_by           UUID REFERENCES users (user_id),
    removed_reason       VARCHAR(20),
    CONSTRAINT uq_document_relationships_tenant_rel UNIQUE (tenant_id, relationship_id),
    CONSTRAINT fk_document_relationships_type FOREIGN KEY (tenant_id, relationship_type_id)
        REFERENCES relationship_types (tenant_id, relationship_type_id),
    CONSTRAINT fk_document_relationships_source FOREIGN KEY (tenant_id, source_document_id)
        REFERENCES documents (tenant_id, document_id),
    CONSTRAINT fk_document_relationships_target FOREIGN KEY (tenant_id, target_document_id)
        REFERENCES documents (tenant_id, document_id),
    CONSTRAINT ck_document_relationships_distinct CHECK (source_document_id <> target_document_id),
    CONSTRAINT ck_document_relationships_origin
        CHECK (origin IN ('manual', 'duplicate_detection', 'bulk', 'version_event')),
    CONSTRAINT ck_document_relationships_removed CHECK (
        (removed_at IS NULL AND removed_by IS NULL AND removed_reason IS NULL)
        OR (removed_at IS NOT NULL AND removed_reason IS NOT NULL
            AND removed_reason IN ('user_removed', 'target_purged')))
);

CREATE UNIQUE INDEX uq_document_relationships_active
    ON document_relationships (tenant_id, relationship_type_id, source_document_id, target_document_id)
    WHERE removed_at IS NULL;
CREATE INDEX ix_document_relationships_source
    ON document_relationships (tenant_id, source_document_id) WHERE removed_at IS NULL;
CREATE INDEX ix_document_relationships_target
    ON document_relationships (tenant_id, target_document_id) WHERE removed_at IS NULL;
CREATE INDEX ix_document_relationships_type
    ON document_relationships (tenant_id, relationship_type_id) WHERE removed_at IS NULL;
CREATE INDEX ix_document_relationships_batch
    ON document_relationships (tenant_id, batch_id) WHERE batch_id IS NOT NULL;
