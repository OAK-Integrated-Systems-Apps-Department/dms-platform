-- Oak DMS Ingestion & Processing (Section 6)
-- outbox_events (6.4) already exists (V2). This adds the remaining tables:
-- 6.1 upload_sessions, 6.2 malware_scan_results, 6.3 processing_jobs,
-- 6.5 renditions, 6.6 ocr_outputs

-- =====================================================================
-- upload_sessions (6.1)
-- =====================================================================
CREATE TABLE upload_sessions (
    upload_session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    initiated_by UUID NOT NULL REFERENCES users (user_id),
    source VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'pending',
    quarantine_object_key VARCHAR(1000),
    document_id UUID REFERENCES documents (document_id),
    correlation_id UUID,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_upload_sessions_source CHECK (source IN ('web', 'mobile', 'desktop', 'api', 'bulk_import')),
    CONSTRAINT ck_upload_sessions_status CHECK (status IN ('pending', 'uploading', 'quarantined', 'completed', 'failed'))
);

CREATE INDEX ix_upload_sessions_tenant_id ON upload_sessions (tenant_id);
CREATE INDEX ix_upload_sessions_initiated_by ON upload_sessions (initiated_by);
CREATE INDEX ix_upload_sessions_document_id ON upload_sessions (document_id);
CREATE INDEX ix_upload_sessions_correlation_id ON upload_sessions (correlation_id);

-- =====================================================================
-- malware_scan_results (6.2, partial standard fields: created_at only)
-- =====================================================================
CREATE TABLE malware_scan_results (
    scan_result_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    version_id UUID REFERENCES document_versions (version_id),
    upload_session_id UUID REFERENCES upload_sessions (upload_session_id),
    scanner_name VARCHAR(255) NOT NULL,
    scan_status VARCHAR(30) NOT NULL,
    scan_response TEXT,
    scanned_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_malware_scan_results_status CHECK (scan_status IN ('clean', 'infected', 'failed', 'quarantined'))
);

CREATE INDEX ix_malware_scan_results_tenant_id ON malware_scan_results (tenant_id);
CREATE INDEX ix_malware_scan_results_version_id ON malware_scan_results (version_id);
CREATE INDEX ix_malware_scan_results_upload_session_id ON malware_scan_results (upload_session_id);

-- =====================================================================
-- processing_jobs (6.3, partial standard fields: created_at only)
-- Cross-cutting: reference_type/reference_id are polymorphic, no FK.
-- =====================================================================
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

-- =====================================================================
-- renditions (6.5, partial standard fields: created_at only)
-- =====================================================================
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

-- =====================================================================
-- ocr_outputs (6.6, partial standard fields: created_at only)
-- =====================================================================
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
