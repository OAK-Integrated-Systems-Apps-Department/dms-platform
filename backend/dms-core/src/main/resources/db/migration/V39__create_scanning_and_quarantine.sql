-- Virus & malware scanning, Phase 1 (com.oak.dms.scanning - see
-- docs/scanning-architecture.md). Adds the scan gate's data model:
--
--   storage_locations.purpose  document | staging | quarantine - staging and
--                              quarantine are ordinary storage locations with
--                              a different job, which is how they get every
--                              storage provider for free.
--   scan_engines               which engine scans a tenant's uploads (tenant
--                              row, else the platform default tenant_id NULL).
--   scan_results               one row per uploaded file, from staging to its
--                              verdict. document_version_id is null until a
--                              clean file is promoted; infected files never
--                              get one.
--   quarantine_records         one row per quarantined file.
--
-- and the gate's database-level enforcement: a deferred constraint trigger on
-- document_versions refuses any new version row that isn't linked to a clean
-- scan_results row with the same file hash, stored in a document-purpose
-- location. That is what forces every current and future DocumentVersion
-- creation path (initial_upload, replacement_upload, workflow_update,
-- signed_copy, migration - including the Python workers, which bypass Java
-- entirely) through the scan gate.
--
-- Composite (tenant_id, X) FKs are used wherever the parent is tenant-owned.
-- scan_engines and storage_locations can't be targeted that way - their
-- platform-default rows have tenant_id NULL - so references to them get a
-- plain FK plus a trigger enforcing "parent is platform-wide or the same
-- tenant" instead.

-- =====================================================================
-- 1. storage_locations.purpose
-- =====================================================================
ALTER TABLE storage_locations
    ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'document',
    ADD CONSTRAINT ck_storage_locations_purpose CHECK (purpose IN ('document', 'staging', 'quarantine'));

DO
$$
DECLARE
    n bigint;
BEGIN
    SELECT count(*) INTO n FROM storage_locations;
    RAISE NOTICE 'V39: % existing storage_locations row(s) defaulted to purpose = document', n;

    SELECT count(*) INTO n
    FROM storage_locations
    WHERE tenant_id IS NULL AND status = 'active' AND is_deleted = false;
    IF n > 1 THEN
        RAISE EXCEPTION 'V39: % active platform-default storage_locations rows exist; expected at most one before adding purpose-scoped uniqueness', n;
    END IF;
END;
$$;

-- One active platform default per purpose (see StorageLocationResolver).
CREATE UNIQUE INDEX uq_storage_locations_platform_purpose
    ON storage_locations (purpose)
    WHERE tenant_id IS NULL AND status = 'active' AND is_deleted = false;

-- A document location can never be relabelled as quarantine/staging, or the
-- reverse - that would silently expose quarantined bytes (or hide documents).
CREATE FUNCTION storage_locations_purpose_immutable() RETURNS trigger AS
$$
BEGIN
    IF NEW.purpose IS DISTINCT FROM OLD.purpose THEN
        RAISE EXCEPTION 'storage_locations.purpose is immutable (location %)', OLD.storage_location_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_storage_locations_purpose_immutable
    BEFORE UPDATE ON storage_locations
    FOR EACH ROW EXECUTE FUNCTION storage_locations_purpose_immutable();

-- =====================================================================
-- 2. scan_engines
-- tenant_id NULL = platform default (seeded by ScanEngineBootstrap).
-- credential_ref is never a raw credential - see ScanEngine's doc comment.
-- =====================================================================
CREATE TABLE scan_engines (
    scan_engine_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants (tenant_id),
    name VARCHAR(150) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    scan_mode VARCHAR(30) NOT NULL,
    endpoint VARCHAR(500),
    region VARCHAR(100),
    credential_ref VARCHAR(255),
    timeout_seconds INT NOT NULL,
    max_file_size_bytes BIGINT NOT NULL,
    -- Pre-serialized JSON string, same convention as storage_locations.configuration.
    configuration TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_scan_engines_provider CHECK (provider IN ('CLAMAV', 'AZURE_DEFENDER', 'AWS_GUARDDUTY')),
    CONSTRAINT ck_scan_engines_scan_mode CHECK (scan_mode IN ('pre_store_stream', 'post_store_object')),
    -- ClamAV scans a stream synchronously; the cloud scanners can only scan an
    -- object after it has landed in their storage.
    CONSTRAINT ck_scan_engines_provider_mode CHECK (
        (provider = 'CLAMAV' AND scan_mode = 'pre_store_stream')
        OR (provider IN ('AZURE_DEFENDER', 'AWS_GUARDDUTY') AND scan_mode = 'post_store_object')),
    CONSTRAINT ck_scan_engines_timeout CHECK (timeout_seconds > 0),
    CONSTRAINT ck_scan_engines_max_file_size CHECK (max_file_size_bytes > 0),
    CONSTRAINT ck_scan_engines_status CHECK (status IN ('active', 'inactive'))
);

CREATE INDEX ix_scan_engines_tenant_id ON scan_engines (tenant_id);

CREATE UNIQUE INDEX uq_scan_engines_tenant_name
    ON scan_engines (tenant_id, name)
    WHERE tenant_id IS NOT NULL;

CREATE UNIQUE INDEX uq_scan_engines_platform_default
    ON scan_engines ((1))
    WHERE tenant_id IS NULL AND status = 'active' AND is_deleted = false;

-- =====================================================================
-- 3. document_versions (tenant_id, version_id) - target for scan_results'
--    composite FK below, same pattern V36 used for documents.
-- =====================================================================
ALTER TABLE document_versions
    ADD CONSTRAINT uq_document_versions_tenant_version UNIQUE (tenant_id, version_id);

-- =====================================================================
-- 4. scan_results
-- Beyond the approved ER diagram (needed for async promotion, retry and
-- crash recovery - see docs/scanning-architecture.md):
--   next_attempt_at / lease_expires_at  retry schedule + claim lease
--   staging_purged_at                   staged object deleted
--   ingestion_kind, created_reason, document_type_id, target_document_id,
--   title                               what to create once the file is clean
-- staging_object_key/file_hash/size_bytes are nullable: null means the file
-- was rejected before staging, or staging is still in progress.
-- =====================================================================
CREATE TABLE scan_results (
    scan_result_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    scan_engine_id UUID NOT NULL REFERENCES scan_engines (scan_engine_id),
    document_version_id UUID,
    staging_location_id UUID NOT NULL REFERENCES storage_locations (storage_location_id),
    staging_object_key VARCHAR(1000),
    original_filename VARCHAR(500) NOT NULL,
    file_hash VARCHAR(255),
    size_bytes BIGINT,
    mime_type VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'pending',
    threat_name VARCHAR(500),
    engine_name VARCHAR(255),
    engine_version VARCHAR(100),
    signature_version VARCHAR(255),
    external_scan_id VARCHAR(500),
    scan_mode VARCHAR(30) NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT,
    attempt_count INT NOT NULL DEFAULT 0,
    failure_reason VARCHAR(1000),
    uploaded_by UUID NOT NULL REFERENCES users (user_id),
    next_attempt_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    staging_purged_at TIMESTAMPTZ,
    ingestion_kind VARCHAR(30) NOT NULL,
    created_reason VARCHAR(50) NOT NULL,
    document_type_id UUID,
    target_document_id UUID,
    title VARCHAR(500),
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_scan_results_tenant_scan_result UNIQUE (tenant_id, scan_result_id),
    CONSTRAINT fk_scan_results_document_version FOREIGN KEY (tenant_id, document_version_id)
        REFERENCES document_versions (tenant_id, version_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_scan_results_target_document FOREIGN KEY (tenant_id, target_document_id)
        REFERENCES documents (tenant_id, document_id),
    CONSTRAINT fk_scan_results_document_type FOREIGN KEY (tenant_id, document_type_id)
        REFERENCES document_types (tenant_id, document_type_id),
    CONSTRAINT ck_scan_results_status CHECK (status IN
        ('pending', 'clean', 'infected', 'failed', 'unscannable', 'quarantined')),
    CONSTRAINT ck_scan_results_scan_mode CHECK (scan_mode IN ('pre_store_stream', 'post_store_object')),
    CONSTRAINT ck_scan_results_ingestion_kind CHECK (ingestion_kind IN ('new_document', 'new_version')),
    CONSTRAINT ck_scan_results_created_reason CHECK (created_reason IN
        ('initial_upload', 'replacement_upload', 'workflow_update', 'signed_copy', 'rollback', 'migration')),
    CONSTRAINT ck_scan_results_version_only_when_clean CHECK (document_version_id IS NULL OR status = 'clean'),
    CONSTRAINT ck_scan_results_threat_named CHECK (status NOT IN ('infected', 'quarantined') OR threat_name IS NOT NULL),
    CONSTRAINT ck_scan_results_clean_complete CHECK (status <> 'clean'
        OR (file_hash IS NOT NULL AND engine_name IS NOT NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_scan_results_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_scan_results_intent CHECK (
        (ingestion_kind = 'new_document' AND document_type_id IS NOT NULL AND title IS NOT NULL)
        OR (ingestion_kind = 'new_version' AND target_document_id IS NOT NULL))
);

CREATE INDEX ix_scan_results_tenant_id ON scan_results (tenant_id);
CREATE INDEX ix_scan_results_document_version_id ON scan_results (document_version_id);
CREATE INDEX ix_scan_results_scan_engine_id ON scan_results (scan_engine_id);

CREATE UNIQUE INDEX uq_scan_results_staging_object
    ON scan_results (staging_location_id, staging_object_key)
    WHERE staging_object_key IS NOT NULL;

-- Drives ScanRetryScheduler's claim query.
CREATE INDEX ix_scan_results_work
    ON scan_results (next_attempt_at)
    WHERE status IN ('pending', 'infected') AND is_deleted = false;

-- Drives ScanRetryScheduler's staging purge sweep.
CREATE INDEX ix_scan_results_purge
    ON scan_results (completed_at)
    WHERE staging_purged_at IS NULL AND staging_object_key IS NOT NULL;

-- scan_engine_id / staging_location_id: platform-wide or the same tenant, and
-- the staging location must actually be a staging location.
CREATE FUNCTION scan_results_check_refs() RETURNS trigger AS
$$
DECLARE
    parent_tenant UUID;
    parent_purpose VARCHAR(20);
BEGIN
    SELECT tenant_id INTO parent_tenant FROM scan_engines WHERE scan_engine_id = NEW.scan_engine_id;
    IF parent_tenant IS NOT NULL AND parent_tenant <> NEW.tenant_id THEN
        RAISE EXCEPTION 'scan_results %: scan engine % belongs to another tenant', NEW.scan_result_id, NEW.scan_engine_id;
    END IF;

    SELECT tenant_id, purpose INTO parent_tenant, parent_purpose
    FROM storage_locations WHERE storage_location_id = NEW.staging_location_id;
    IF parent_tenant IS NOT NULL AND parent_tenant <> NEW.tenant_id THEN
        RAISE EXCEPTION 'scan_results %: staging location % belongs to another tenant', NEW.scan_result_id, NEW.staging_location_id;
    END IF;
    IF parent_purpose <> 'staging' THEN
        RAISE EXCEPTION 'scan_results %: location % is not a staging location (purpose=%)',
            NEW.scan_result_id, NEW.staging_location_id, parent_purpose;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_scan_results_check_refs
    BEFORE INSERT OR UPDATE OF tenant_id, scan_engine_id, staging_location_id ON scan_results
    FOR EACH ROW EXECUTE FUNCTION scan_results_check_refs();

-- Once a scan result is linked to the version it produced, its verdict, hash
-- and link are frozen - the version's own "scanned" guarantee rests on them.
CREATE FUNCTION scan_results_link_immutable() RETURNS trigger AS
$$
BEGIN
    IF OLD.document_version_id IS NOT NULL AND (
            NEW.document_version_id IS DISTINCT FROM OLD.document_version_id
            OR NEW.status IS DISTINCT FROM OLD.status
            OR NEW.file_hash IS DISTINCT FROM OLD.file_hash) THEN
        RAISE EXCEPTION 'scan_results %: status, file_hash and document_version_id are immutable once linked to a version',
            OLD.scan_result_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_scan_results_link_immutable
    BEFORE UPDATE ON scan_results
    FOR EACH ROW EXECUTE FUNCTION scan_results_link_immutable();

-- =====================================================================
-- 5. quarantine_records
-- =====================================================================
CREATE TABLE quarantine_records (
    quarantine_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    scan_result_id UUID NOT NULL,
    quarantine_location_id UUID NOT NULL REFERENCES storage_locations (storage_location_id),
    quarantine_object_key VARCHAR(1000) NOT NULL,
    original_filename VARCHAR(500) NOT NULL,
    file_hash VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    threat_name VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'held',
    reviewed_by UUID REFERENCES users (user_id),
    reviewed_at TIMESTAMPTZ,
    review_reason VARCHAR(1000),
    released_document_id UUID,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_quarantine_records_scan_result UNIQUE (scan_result_id),
    CONSTRAINT fk_quarantine_records_scan_result FOREIGN KEY (tenant_id, scan_result_id)
        REFERENCES scan_results (tenant_id, scan_result_id),
    CONSTRAINT fk_quarantine_records_released_document FOREIGN KEY (tenant_id, released_document_id)
        REFERENCES documents (tenant_id, document_id),
    CONSTRAINT ck_quarantine_records_status CHECK (status IN ('held', 'released', 'deleted')),
    CONSTRAINT ck_quarantine_records_reviewed CHECK (status = 'held'
        OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CONSTRAINT ck_quarantine_records_released_document CHECK (released_document_id IS NULL OR status = 'released')
);

CREATE INDEX ix_quarantine_records_tenant_status ON quarantine_records (tenant_id, status);

CREATE FUNCTION quarantine_records_check_refs() RETURNS trigger AS
$$
DECLARE
    parent_tenant UUID;
    parent_purpose VARCHAR(20);
BEGIN
    SELECT tenant_id, purpose INTO parent_tenant, parent_purpose
    FROM storage_locations WHERE storage_location_id = NEW.quarantine_location_id;
    IF parent_tenant IS NOT NULL AND parent_tenant <> NEW.tenant_id THEN
        RAISE EXCEPTION 'quarantine_records %: quarantine location % belongs to another tenant',
            NEW.quarantine_id, NEW.quarantine_location_id;
    END IF;
    IF parent_purpose <> 'quarantine' THEN
        RAISE EXCEPTION 'quarantine_records %: location % is not a quarantine location (purpose=%)',
            NEW.quarantine_id, NEW.quarantine_location_id, parent_purpose;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_quarantine_records_check_refs
    BEFORE INSERT OR UPDATE OF tenant_id, quarantine_location_id ON quarantine_records
    FOR EACH ROW EXECUTE FUNCTION quarantine_records_check_refs();

-- =====================================================================
-- 6. The scan gate, enforced by the database.
-- Deferred to commit, so DocumentVersionPromoter can insert the version and
-- link the scan result in either order within one transaction (same reason
-- V32's documents <-> document_versions FKs are DEFERRABLE).
-- =====================================================================
CREATE FUNCTION document_versions_require_clean_scan() RETURNS trigger AS
$$
BEGIN
    IF NEW.scan_status <> 'clean' THEN
        RAISE EXCEPTION 'document_versions %: a new version must be created with scan_status = clean (got %)',
            NEW.version_id, NEW.scan_status;
    END IF;

    IF NOT EXISTS (SELECT 1
                   FROM storage_locations sl
                   WHERE sl.storage_location_id = NEW.storage_location_id
                     AND sl.purpose = 'document') THEN
        RAISE EXCEPTION 'document_versions %: storage location % is not a document location',
            NEW.version_id, NEW.storage_location_id;
    END IF;

    IF NOT EXISTS (SELECT 1
                   FROM scan_results sr
                   WHERE sr.tenant_id = NEW.tenant_id
                     AND sr.document_version_id = NEW.version_id
                     AND sr.status = 'clean'
                     AND sr.file_hash = NEW.file_hash
                     AND sr.is_deleted = false) THEN
        RAISE EXCEPTION 'document_versions %: no clean scan result with a matching file hash is linked to this version - every version must pass through the scan gate',
            NEW.version_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_document_versions_require_clean_scan
    AFTER INSERT ON document_versions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION document_versions_require_clean_scan();

-- A scanned version can't later be pointed at other bytes, or lose its verdict.
CREATE FUNCTION document_versions_content_immutable() RETURNS trigger AS
$$
BEGIN
    IF NEW.storage_location_id IS DISTINCT FROM OLD.storage_location_id
        OR NEW.storage_object_key IS DISTINCT FROM OLD.storage_object_key
        OR NEW.file_hash IS DISTINCT FROM OLD.file_hash THEN
        RAISE EXCEPTION 'document_versions %: storage_location_id, storage_object_key and file_hash are immutable',
            OLD.version_id;
    END IF;
    IF OLD.scan_status = 'clean' AND NEW.scan_status <> 'clean' THEN
        RAISE EXCEPTION 'document_versions %: scan_status cannot move away from clean', OLD.version_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_document_versions_content_immutable
    BEFORE UPDATE ON document_versions
    FOR EACH ROW EXECUTE FUNCTION document_versions_content_immutable();

-- =====================================================================
-- 7. Legacy versions uploaded before scanning existed stay 'pending' and are
--    refused by DocumentServiceImpl#downloadDocument until rescanned.
-- =====================================================================
DO
$$
DECLARE
    n bigint;
BEGIN
    SELECT count(*) INTO n FROM document_versions WHERE scan_status <> 'clean';
    RAISE NOTICE 'V39: % legacy document_versions row(s) were never scanned and are now blocked from download', n;
END;
$$;
