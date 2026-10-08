-- Module 2.1 File Import/Export - export_jobs (Table Dictionary 11.6).
--
-- One row per requested export. This migration only creates the table; the
-- code that generates an export and fills file_object_key (FIE-FR-010) and
-- the auto-purge sweep over expires_at arrive in Phase 4.
--
--   export_type      single_document | bulk_folder | report_data
--   status           pending | processing | completed | failed | expired
--   file_object_key  object-storage key of the generated file, e.g.
--                    /{tenant_id}/tmp/exports/{export_id}/batch.zip
--   expires_at       auto-purge deadline. The TTL used to set it is
--                    configurable (dms.export.job-ttl) - its real value is
--                    Phase 0 Key Decision #10, still open.
--   correlation_id   ties the export to its download/audit events; UUID like
--                    audit_events.correlation_id (V23).
--
-- requested_by is a plain FK to users, the same as every other user
-- reference (quarantine_records.reviewed_by, created_by, ...); users has no
-- (tenant_id, user_id) key to target with a composite FK.

CREATE TABLE export_jobs (
    export_job_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    requested_by UUID NOT NULL REFERENCES users (user_id),
    export_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    file_object_key VARCHAR(1000),
    expires_at TIMESTAMPTZ,
    correlation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_export_jobs_export_type CHECK (export_type IN ('single_document', 'bulk_folder', 'report_data')),
    CONSTRAINT ck_export_jobs_status CHECK (status IN ('pending', 'processing', 'completed', 'failed', 'expired'))
);

CREATE INDEX ix_export_jobs_tenant_id ON export_jobs (tenant_id);
CREATE INDEX ix_export_jobs_status ON export_jobs (status);
-- Serves the Phase 4 purge sweep: WHERE expires_at < now().
CREATE INDEX ix_export_jobs_expires_at ON export_jobs (expires_at);
