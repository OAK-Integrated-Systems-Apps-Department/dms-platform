-- Module 2.1 - File Import/Export.
--
-- Two tables:
--   import_batches - one bulk import / migration run against a single
--                    document type at a pinned schema_version
--   import_items   - one row of that run's manifest, with its raw values,
--                    per-row outcome and validation errors
--
-- Conventions mirrored from V28/V32: uuid PK DEFAULT gen_random_uuid(),
-- tenant_id UUID NOT NULL REFERENCES tenants, created_by/updated_by as bare
-- UUID REFERENCES users (no JPA relation), status as VARCHAR + CHECK (never
-- a Postgres enum), and an index on every FK column.

-- =====================================================================
-- import_batches
-- =====================================================================
CREATE TABLE import_batches (
    import_batch_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (tenant_id),
    source_type         VARCHAR(30) NOT NULL,
    document_type_id    UUID NOT NULL REFERENCES document_types (document_type_id),
    schema_version      INT NOT NULL,
    status              VARCHAR(30) NOT NULL DEFAULT 'uploading',
    total_records       INT NOT NULL DEFAULT 0,
    success_count       INT NOT NULL DEFAULT 0,
    failure_count       INT NOT NULL DEFAULT 0,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    correlation_id      UUID NOT NULL,
    created_by          UUID REFERENCES users (user_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID REFERENCES users (user_id),
    updated_at          TIMESTAMPTZ,
    is_deleted          BOOLEAN NOT NULL DEFAULT false,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT ck_import_batches_source_type CHECK (source_type IN ('bulk_import', 'migration')),
    CONSTRAINT ck_import_batches_status CHECK (status IN
        ('uploading', 'queued', 'running', 'completed', 'completed_with_errors', 'failed'))
);

-- Leading tenant_id also serves plain tenant lookups, so no separate
-- ix_import_batches_tenant_id.
CREATE INDEX ix_import_batches_tenant_status ON import_batches (tenant_id, status);
CREATE INDEX ix_import_batches_document_type_id ON import_batches (document_type_id);

-- =====================================================================
-- import_items
-- =====================================================================
CREATE TABLE import_items (
    import_item_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (tenant_id),
    import_batch_id     UUID NOT NULL REFERENCES import_batches (import_batch_id),
    row_number          INT NOT NULL,
    -- Deliberately not unique: a manifest listing the same file twice is
    -- reported as a per-row validation failure, not blocked by the database.
    filename_reference  VARCHAR(500) NOT NULL,
    row_values          JSONB NOT NULL,
    -- TODO: FK to be added once the branch that creates upload_sessions is
    -- merged. Do not create upload_sessions here.
    upload_session_id   UUID,
    document_id         UUID REFERENCES documents (document_id),
    status              VARCHAR(30) NOT NULL DEFAULT 'pending',
    validation_errors   JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    CONSTRAINT ck_import_items_status CHECK (status IN ('pending', 'success', 'failed')),
    -- Its backing index leads with import_batch_id, so it also serves
    -- per-batch lookups - no separate ix_import_items_import_batch_id.
    CONSTRAINT uq_import_items_batch_row_number UNIQUE (import_batch_id, row_number)
);

CREATE INDEX ix_import_items_tenant_id ON import_items (tenant_id);
CREATE INDEX ix_import_items_upload_session_id ON import_items (upload_session_id);
CREATE INDEX ix_import_items_document_id ON import_items (document_id);
