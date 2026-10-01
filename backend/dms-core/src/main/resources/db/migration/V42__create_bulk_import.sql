-- Module 2.1 File Import/Export - template-based bulk import.
--
--   migration_batches (11.4)  one uploaded template + its files
--   migration_items   (11.5)  one row of that template
--   upload_sessions   (6.1)   resumable, chunked upload of a large ZIP
--
-- Columns follow the Table Dictionary; everything marked "ADDED" is not in
-- the dictionary and exists because the FIE requirements can't be met
-- without it (each says which). Composite (tenant_id, X) FKs wherever the
-- parent is tenant-owned, like V39.

-- =====================================================================
-- migration_batches (11.4)
-- =====================================================================
CREATE TABLE migration_batches (
    migration_batch_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    source_system VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'pending',
    total_records INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    -- ADDED: which document type the template (and so every row) is for.
    document_type_id UUID NOT NULL,
    -- ADDED: schema version the template was generated from; a template for
    -- an older version is rejected at parsing (alternate flow 2).
    schema_version INT NOT NULL,
    -- ADDED: xlsx | csv - the failed-rows report comes back in the same format (FIE-FR-009).
    template_format VARCHAR(10) NOT NULL,
    -- ADDED: client-chosen key so a retried submission (network failure)
    -- returns the existing batch instead of importing twice (FIE-NFR-004).
    idempotency_key VARCHAR(100),
    -- ADDED: set by "cancel"; the worker stops before the next row (alternate flow 6).
    cancel_requested BOOLEAN NOT NULL DEFAULT false,
    -- ADDED: batch-level failure (unreadable template, wrong version, bad ZIP).
    failure_reason VARCHAR(1000),
    created_by UUID NOT NULL REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_migration_batches_tenant_batch UNIQUE (tenant_id, migration_batch_id),
    CONSTRAINT fk_migration_batches_document_type FOREIGN KEY (tenant_id, document_type_id)
        REFERENCES document_types (tenant_id, document_type_id),
    -- 'cancelled' ADDED to the dictionary's list (alternate flow 6).
    CONSTRAINT ck_migration_batches_status CHECK (status IN
        ('pending', 'running', 'completed', 'completed_with_errors', 'failed', 'cancelled')),
    CONSTRAINT ck_migration_batches_template_format CHECK (template_format IN ('xlsx', 'csv')),
    CONSTRAINT ck_migration_batches_counts CHECK (total_records >= 0 AND success_count >= 0 AND failure_count >= 0
        AND success_count + failure_count <= total_records)
);

CREATE INDEX ix_migration_batches_tenant_created ON migration_batches (tenant_id, created_at DESC);
CREATE INDEX ix_migration_batches_status ON migration_batches (status);
CREATE UNIQUE INDEX uq_migration_batches_idempotency
    ON migration_batches (tenant_id, created_by, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- =====================================================================
-- migration_items (11.5)
-- =====================================================================
CREATE TABLE migration_items (
    migration_item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    migration_batch_id UUID NOT NULL,
    -- The template's filename reference (FIE-FR-004).
    source_reference VARCHAR(500) NOT NULL,
    document_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    -- Human-readable reasons, one per line: "field: reason" (FIE-NFR-005).
    validation_result TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ADDED: spreadsheet row number, as the user sees it, for the report (FIE-NFR-005).
    row_number INT NOT NULL,
    -- ADDED: the row's original cell values, so failed rows can be handed
    -- back as a correctable template (FIE-FR-009).
    row_data JSONB NOT NULL,
    -- ADDED: when the item last changed (processing / result).
    updated_at TIMESTAMPTZ,
    -- ADDED: the row's malware scan. Set when the file was handed to the scan
    -- gate but the scan hadn't finished (cloud scanners, ClamAV retry) - the
    -- reconciler finishes the row once that scan resolves.
    scan_result_id UUID,
    CONSTRAINT fk_migration_items_batch FOREIGN KEY (tenant_id, migration_batch_id)
        REFERENCES migration_batches (tenant_id, migration_batch_id),
    CONSTRAINT fk_migration_items_scan_result FOREIGN KEY (tenant_id, scan_result_id)
        REFERENCES scan_results (tenant_id, scan_result_id),
    CONSTRAINT fk_migration_items_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id),
    -- 'cancelled' ADDED: rows never reached because the batch was cancelled.
    CONSTRAINT ck_migration_items_status CHECK (status IN ('pending', 'success', 'failed', 'cancelled')),
    CONSTRAINT ck_migration_items_success_has_document CHECK (status <> 'success' OR document_id IS NOT NULL),
    CONSTRAINT uq_migration_items_batch_row UNIQUE (migration_batch_id, row_number)
);

CREATE INDEX ix_migration_items_batch_status ON migration_items (migration_batch_id, status);
CREATE INDEX ix_migration_items_document_id ON migration_items (document_id);
-- Serves the reconciler: rows still waiting for their scan.
CREATE INDEX ix_migration_items_awaiting_scan ON migration_items (scan_result_id)
    WHERE status = 'pending' AND scan_result_id IS NOT NULL;

-- =====================================================================
-- upload_sessions (6.1) - resumable chunked upload for large ZIPs (FIE-NFR-002/004)
-- =====================================================================
CREATE TABLE upload_sessions (
    upload_session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    initiated_by UUID NOT NULL REFERENCES users (user_id),
    source VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'pending',
    quarantine_object_key VARCHAR(1000),
    document_id UUID,
    correlation_id UUID,
    -- ADDED: what's being uploaded and how far it got - the resume point.
    original_filename VARCHAR(500) NOT NULL,
    total_size_bytes BIGINT NOT NULL,
    received_bytes BIGINT NOT NULL DEFAULT 0,
    -- ADDED: where the received bytes are staged until the session is used.
    staging_path VARCHAR(1000),
    -- ADDED: abandoned sessions are cleaned up after this.
    expires_at TIMESTAMPTZ NOT NULL,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_upload_sessions_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id),
    CONSTRAINT ck_upload_sessions_source CHECK (source IN ('web', 'mobile', 'desktop', 'api', 'bulk_import')),
    CONSTRAINT ck_upload_sessions_status CHECK (status IN ('pending', 'uploading', 'quarantined', 'completed', 'failed')),
    CONSTRAINT ck_upload_sessions_bytes CHECK (total_size_bytes > 0 AND received_bytes >= 0
        AND received_bytes <= total_size_bytes)
);

CREATE INDEX ix_upload_sessions_tenant_id ON upload_sessions (tenant_id);
CREATE INDEX ix_upload_sessions_initiated_by ON upload_sessions (initiated_by);
CREATE INDEX ix_upload_sessions_document_id ON upload_sessions (document_id);
CREATE INDEX ix_upload_sessions_correlation_id ON upload_sessions (correlation_id);
CREATE INDEX ix_upload_sessions_expires_at ON upload_sessions (expires_at);
