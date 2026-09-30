-- Module 2.1 - File Import/Export.
--
-- One table:
--   export_jobs - one queued export request (a single document, a bulk
--                 selection, a folder, or report data), its progress, and
--                 the generated file until it expires
--
-- Conventions mirrored from V28/V32/V41: uuid PK DEFAULT gen_random_uuid(),
-- tenant_id UUID NOT NULL REFERENCES tenants, created_by/updated_by as bare
-- UUID REFERENCES users (no JPA relation), status-style columns as VARCHAR +
-- CHECK (never a Postgres enum), and file_object_key sized like V32's
-- storage_object_key.
--
-- Deliberately differs from the Table Dictionary (11.6):
--   - No requested_by column. created_by records the requester, and the
--     Table Dictionary forbids duplicating standard fields.
--   - bulk_selection, metadata_format, request_params, document_count,
--     file_size_bytes, failure_reason, started_at, completed_at and
--     expiry_warning_sent_at were added for FIE-FR-010/011 and the
--     queue-based export flow.

-- =====================================================================
-- export_jobs
-- =====================================================================
CREATE TABLE export_jobs (
    export_job_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES tenants (tenant_id),
    export_type            VARCHAR(30) NOT NULL,
    metadata_format        VARCHAR(10) NOT NULL,
    -- What to export (e.g. the selected document IDs); read by the export worker.
    request_params         JSONB NOT NULL,
    status                 VARCHAR(30) NOT NULL DEFAULT 'pending',
    document_count         INT NOT NULL DEFAULT 0,
    file_object_key        VARCHAR(1000),
    file_size_bytes        BIGINT,
    failure_reason         TEXT,
    started_at             TIMESTAMPTZ,
    completed_at           TIMESTAMPTZ,
    expires_at             TIMESTAMPTZ,
    expiry_warning_sent_at TIMESTAMPTZ,
    correlation_id         UUID NOT NULL,
    created_by             UUID REFERENCES users (user_id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by             UUID REFERENCES users (user_id),
    updated_at             TIMESTAMPTZ,
    is_deleted             BOOLEAN NOT NULL DEFAULT false,
    deleted_at             TIMESTAMPTZ,
    CONSTRAINT ck_export_jobs_export_type CHECK (export_type IN
        ('single_document', 'bulk_selection', 'bulk_folder', 'report_data')),
    CONSTRAINT ck_export_jobs_metadata_format CHECK (metadata_format IN ('xlsx', 'csv')),
    CONSTRAINT ck_export_jobs_status CHECK (status IN
        ('pending', 'processing', 'completed', 'failed', 'expired'))
);

-- Leading tenant_id also serves plain tenant lookups, so no separate
-- ix_export_jobs_tenant_id.
CREATE INDEX ix_export_jobs_tenant_status ON export_jobs (tenant_id, status);
-- Backs the expiry cleanup job.
CREATE INDEX ix_export_jobs_expires_at ON export_jobs (expires_at);
