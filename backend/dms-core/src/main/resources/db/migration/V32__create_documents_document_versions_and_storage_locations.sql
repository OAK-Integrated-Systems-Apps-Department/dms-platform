-- Pluggable object storage architecture (see docs/storage-architecture.md) +
-- the documents/document_versions tables com.oak.dms.document.Document's own
-- javadoc already refers to as "V32". Ported from docs/database/V4 4.1/4.2,
-- with folder_id/document_relationships/tags omitted (nothing in this
-- integration needs them yet - see Document.java's own doc comment) and
-- storage_location_id added to document_versions (not documents - see
-- storage-architecture.md's "why DocumentVersion, not Document" section:
-- the physical object's location belongs on the same row as its key).
--
-- storage_locations must be created first: document_versions has a NOT NULL
-- FK to it. documents <-> document_versions is the same circular reference
-- V4 already designed for (documents points at its current/latest/published
-- version; document_versions points back to its parent document) - documents
-- is created first WITHOUT those three FKs, document_versions is created
-- next, and the three FKs are added to documents afterwards via ALTER TABLE,
-- all DEFERRABLE INITIALLY DEFERRED. That's what lets DocumentServiceImpl
-- persist a Document and its first DocumentVersion (both client-generated
-- UUIDs) in either order within one @Transactional method - same pattern
-- V1 already uses for users.created_by/users.updated_by.

-- =====================================================================
-- storage_locations
-- tenant_id NULL means "platform-wide default", selectable by any tenant
-- that hasn't registered its own location (see StorageLocationResolver /
-- StorageLocationBootstrap). credential_ref is NEVER a raw access/secret
-- key - it's an indirection key the S3 adapter resolves against
-- environment variables at call time (STORAGE_CRED_<ref>_ACCESS_KEY/
-- _SECRET_KEY); a null/blank ref falls back to this deployment's single
-- dms.storage.s3.* configuration. Nothing here ever stores a credential.
-- =====================================================================
CREATE TABLE storage_locations (
    storage_location_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants (tenant_id),
    name VARCHAR(150) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    endpoint VARCHAR(500),
    region VARCHAR(100),
    bucket VARCHAR(255),
    base_path VARCHAR(1000),
    path_style_access BOOLEAN NOT NULL DEFAULT false,
    credential_ref VARCHAR(255),
    -- Pre-serialized JSON string (same convention as audit_events.previous_value/
    -- new_value), not a native jsonb column - an escape hatch for provider-specific
    -- extras a future adapter (e.g. Azure Blob's connection-string shape) needs
    -- without another migration adding another rigid column.
    configuration TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_storage_locations_provider CHECK (provider IN ('S3', 'FILE_SYSTEM')),
    CONSTRAINT ck_storage_locations_status CHECK (status IN ('active', 'inactive'))
);

CREATE INDEX ix_storage_locations_tenant_id ON storage_locations (tenant_id);

-- A tenant's own named locations must be unique to that tenant; the platform-
-- wide default row(s) (tenant_id IS NULL) are exempt (see V13's identical
-- reasoning for why this must be two partial indexes, not one plain
-- UNIQUE(tenant_id, name) constraint - Postgres treats every NULL as
-- distinct for uniqueness purposes).
CREATE UNIQUE INDEX uq_storage_locations_tenant_name
    ON storage_locations (tenant_id, name)
    WHERE tenant_id IS NOT NULL;

-- =====================================================================
-- documents (4.1, minus folder_id)
-- current_version_id / latest_version_id / published_version_id are added
-- via ALTER TABLE below, once document_versions exists.
-- =====================================================================
CREATE TABLE documents (
    document_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_type_id UUID NOT NULL REFERENCES document_types (document_type_id),
    title VARCHAR(500) NOT NULL,
    current_version_id UUID,
    latest_version_id UUID NOT NULL,
    published_version_id UUID,
    owner_user_id UUID NOT NULL REFERENCES users (user_id),
    status VARCHAR(30) NOT NULL DEFAULT 'draft',
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_documents_status CHECK (status IN ('draft', 'published', 'archived', 'pending-deletion'))
);

CREATE INDEX ix_documents_tenant_id ON documents (tenant_id);
CREATE INDEX ix_documents_document_type_id ON documents (document_type_id);
CREATE INDEX ix_documents_owner_user_id ON documents (owner_user_id);

-- =====================================================================
-- document_versions (4.2 + storage_location_id)
-- =====================================================================
CREATE TABLE document_versions (
    version_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    version_number INT NOT NULL,
    major_version INT NOT NULL,
    minor_version INT NOT NULL,
    version_label VARCHAR(100),
    -- The physical object's location and its key within that location -
    -- deliberately on this table, not documents, since each version is a
    -- distinct physical object (see storage-architecture.md). Never used
    -- as the sole tenant-isolation boundary - see DocumentServiceImpl's
    -- explicit re-validation before every storage call.
    storage_location_id UUID NOT NULL REFERENCES storage_locations (storage_location_id),
    storage_object_key VARCHAR(1000) NOT NULL,
    original_filename VARCHAR(500) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    file_hash VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    version_comment VARCHAR(1000),
    created_reason VARCHAR(50) NOT NULL,
    scan_status VARCHAR(30) NOT NULL DEFAULT 'pending',
    rendition_status VARCHAR(30) NOT NULL DEFAULT 'not_required',
    ocr_status VARCHAR(30) NOT NULL DEFAULT 'not_required',
    is_protected BOOLEAN NOT NULL DEFAULT false,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_document_versions_document_number UNIQUE (document_id, version_number),
    CONSTRAINT ck_document_versions_created_reason CHECK (created_reason IN
        ('initial_upload', 'replacement_upload', 'workflow_update', 'signed_copy', 'rollback', 'migration')),
    CONSTRAINT ck_document_versions_scan_status CHECK (scan_status IN
        ('pending', 'clean', 'infected', 'failed', 'quarantined')),
    CONSTRAINT ck_document_versions_rendition_status CHECK (rendition_status IN
        ('not_required', 'pending', 'processing', 'completed', 'failed', 'unsupported')),
    CONSTRAINT ck_document_versions_ocr_status CHECK (ocr_status IN
        ('not_required', 'pending', 'processing', 'completed', 'failed', 'unsupported'))
);

CREATE INDEX ix_document_versions_document_id ON document_versions (document_id);
CREATE INDEX ix_document_versions_tenant_id ON document_versions (tenant_id);
CREATE INDEX ix_document_versions_storage_location_id ON document_versions (storage_location_id);

-- Deferred: documents.current_version_id / latest_version_id / published_version_id
-- reference document_versions.version_id, added now that the table exists.
-- DEFERRABLE INITIALLY DEFERRED so DocumentServiceImpl can insert both rows
-- (each referencing the other) in either order within one transaction.
ALTER TABLE documents
    ADD CONSTRAINT fk_documents_current_version FOREIGN KEY (current_version_id)
        REFERENCES document_versions (version_id) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_documents_latest_version FOREIGN KEY (latest_version_id)
        REFERENCES document_versions (version_id) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_documents_published_version FOREIGN KEY (published_version_id)
        REFERENCES document_versions (version_id) DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX ix_documents_current_version_id ON documents (current_version_id);
CREATE INDEX ix_documents_latest_version_id ON documents (latest_version_id);
CREATE INDEX ix_documents_published_version_id ON documents (published_version_id);

-- document_versions.document_id was created NOT NULL + FK already above,
-- but not deferrable by default - redeclare it deferrable so the same
-- either-order-within-one-transaction guarantee holds from both sides.
ALTER TABLE document_versions
    DROP CONSTRAINT document_versions_document_id_fkey,
    ADD CONSTRAINT fk_document_versions_document FOREIGN KEY (document_id)
        REFERENCES documents (document_id) DEFERRABLE INITIALLY DEFERRED;

-- Resolves the TODO left on document_metadata_values.document_id by
-- V28__create_metadata_document_type_tables.sql ("FK to documents(document_id)
-- once the documents table exists") - documents now exists.
ALTER TABLE document_metadata_values
    ADD CONSTRAINT fk_document_metadata_values_document FOREIGN KEY (document_id)
        REFERENCES documents (document_id);
