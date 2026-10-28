-- Ingestion & processing (docs/database/V5) -- ocr-worker
-- (backend/workers/ocr-worker) reads/writes processing_jobs and
-- ocr_outputs directly; dms-core does not own writes to those two tables,
-- but they must exist here since this is the database ocr-worker connects
-- to.
--
-- MERGE NOTE (OCR branch into the watermarking lineage): this migration
-- originally also created upload_sessions and malware_scan_results. Both
-- are dropped here because the main lineage already builds those
-- capabilities, and builds them properly:
--   * upload_sessions      -> V42 (create_bulk_import), a strict superset
--                             of the version here plus the resumable-upload
--                             columns (original_filename, total_size_bytes,
--                             received_bytes, staging_path, expires_at).
--   * malware_scan_results -> V39 (create_scanning_and_quarantine), a real
--                             implementation (scan_engines, scan_results,
--                             quarantine_records) rather than the
--                             placeholder this file carried. Nothing in
--                             dms-core or ocr-worker ever read it.
-- The original header described both as present "for schema completeness
-- ... even though nothing writes to them yet", so nothing is lost.
--
-- `renditions` IS kept: ocr-worker writes one searchable_pdf row per
-- version to it (see its models.py). It is NOT the same thing as V64's
-- document_versions.rendition_object_key, which is the preview PDF
-- DocumentRenditionService makes for Office files. A preview is a picture
-- of the document; a searchable PDF carries a text layer. Both exist.

CREATE TABLE processing_jobs (
    job_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    job_type VARCHAR(30) NOT NULL,
    reference_type VARCHAR(100) NOT NULL,
    reference_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'queued',
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    correlation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_processing_jobs_job_type CHECK (job_type IN
        ('ocr', 'rendition', 'indexing', 'export', 'import_validation', 'notification')),
    CONSTRAINT ck_processing_jobs_status CHECK (status IN
        ('queued', 'processing', 'completed', 'failed', 'dead_letter'))
);

CREATE INDEX ix_processing_jobs_tenant_id ON processing_jobs (tenant_id);
CREATE INDEX ix_processing_jobs_status ON processing_jobs (status);
CREATE INDEX ix_processing_jobs_reference ON processing_jobs (reference_type, reference_id);
CREATE INDEX ix_processing_jobs_correlation_id ON processing_jobs (correlation_id);

CREATE TABLE renditions (
    rendition_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    version_id UUID NOT NULL REFERENCES document_versions (version_id),
    rendition_type VARCHAR(30) NOT NULL,
    storage_object_key VARCHAR(1000) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_renditions_type CHECK (rendition_type IN ('thumbnail', 'preview', 'pdf')),
    CONSTRAINT ck_renditions_status CHECK (status IN
        ('pending', 'processing', 'completed', 'failed', 'unsupported'))
);

CREATE INDEX ix_renditions_tenant_id ON renditions (tenant_id);
CREATE INDEX ix_renditions_version_id ON renditions (version_id);

CREATE TABLE ocr_outputs (
    ocr_output_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    version_id UUID NOT NULL REFERENCES document_versions (version_id),
    extracted_text_ref VARCHAR(1000) NOT NULL,
    language VARCHAR(30),
    status VARCHAR(30) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ocr_outputs_status CHECK (status IN
        ('pending', 'processing', 'completed', 'failed', 'unsupported'))
);

CREATE INDEX ix_ocr_outputs_tenant_id ON ocr_outputs (tenant_id);
CREATE INDEX ix_ocr_outputs_version_id ON ocr_outputs (version_id);
