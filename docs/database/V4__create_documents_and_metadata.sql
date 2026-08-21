-- Oak DMS Documents & Organization (Section 4) + Metadata Schema & Values (Section 5)
-- document_types is created first (out of dictionary order) because documents.document_type_id
-- references it. documents <-> document_versions is a circular reference (documents points to
-- its current/latest/published version; document_versions points back to its parent document),
-- so documents is created first WITHOUT those three FKs, document_versions is created next, and
-- the three FKs are added to documents afterwards via ALTER TABLE -- the same pattern used for
-- users.created_by / users.updated_by in V1.

-- =====================================================================
-- document_types (5.1)
-- =====================================================================
CREATE TABLE document_types (
    document_type_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_type_name VARCHAR(255) NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_document_types_tenant_name UNIQUE (tenant_id, document_type_name)
);

CREATE INDEX ix_document_types_tenant_id ON document_types (tenant_id);

-- =====================================================================
-- folders (4.4) -- self-referencing hierarchy
-- =====================================================================
CREATE TABLE folders (
    folder_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    parent_folder_id UUID REFERENCES folders (folder_id),
    folder_name VARCHAR(255) NOT NULL,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX ix_folders_tenant_id ON folders (tenant_id);
CREATE INDEX ix_folders_parent_folder_id ON folders (parent_folder_id);

-- =====================================================================
-- documents (4.1)
-- current_version_id / latest_version_id / published_version_id are added
-- via ALTER TABLE below, once document_versions exists.
-- =====================================================================
CREATE TABLE documents (
    document_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_type_id UUID NOT NULL REFERENCES document_types (document_type_id),
    folder_id UUID REFERENCES folders (folder_id),
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
CREATE INDEX ix_documents_folder_id ON documents (folder_id);
CREATE INDEX ix_documents_owner_user_id ON documents (owner_user_id);

-- =====================================================================
-- document_versions (4.2)
-- =====================================================================
CREATE TABLE document_versions (
    version_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    version_number INT NOT NULL,
    major_version INT NOT NULL,
    minor_version INT NOT NULL,
    version_label VARCHAR(100),
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

-- Deferred: documents.current_version_id / latest_version_id / published_version_id
-- reference document_versions.version_id, added now that the table exists.
ALTER TABLE documents
    ADD CONSTRAINT fk_documents_current_version FOREIGN KEY (current_version_id) REFERENCES document_versions (version_id),
    ADD CONSTRAINT fk_documents_latest_version FOREIGN KEY (latest_version_id) REFERENCES document_versions (version_id),
    ADD CONSTRAINT fk_documents_published_version FOREIGN KEY (published_version_id) REFERENCES document_versions (version_id);

CREATE INDEX ix_documents_current_version_id ON documents (current_version_id);
CREATE INDEX ix_documents_latest_version_id ON documents (latest_version_id);
CREATE INDEX ix_documents_published_version_id ON documents (published_version_id);

-- =====================================================================
-- document_relationships (4.3)
-- =====================================================================
CREATE TABLE document_relationships (
    relationship_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_document_id UUID NOT NULL REFERENCES documents (document_id),
    target_document_id UUID NOT NULL REFERENCES documents (document_id),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    relationship_type VARCHAR(50) NOT NULL,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX ix_document_relationships_source_document_id ON document_relationships (source_document_id);
CREATE INDEX ix_document_relationships_target_document_id ON document_relationships (target_document_id);
CREATE INDEX ix_document_relationships_tenant_id ON document_relationships (tenant_id);

-- =====================================================================
-- tags (4.5)
-- =====================================================================
CREATE TABLE tags (
    tag_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    tag_name VARCHAR(100) NOT NULL,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_tags_tenant_name UNIQUE (tenant_id, tag_name)
);

CREATE INDEX ix_tags_tenant_id ON tags (tenant_id);

-- =====================================================================
-- document_tags (4.6, partial standard fields: created_at only)
-- =====================================================================
CREATE TABLE document_tags (
    document_tag_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    tag_id UUID NOT NULL REFERENCES tags (tag_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_tags UNIQUE (document_id, tag_id)
);

CREATE INDEX ix_document_tags_document_id ON document_tags (document_id);
CREATE INDEX ix_document_tags_tag_id ON document_tags (tag_id);
CREATE INDEX ix_document_tags_tenant_id ON document_tags (tenant_id);

-- =====================================================================
-- metadata_fields (5.2)
-- reference_list_id has no defined target table in the Table Dictionary
-- ("points to the allowed value list") -- stored as a plain UUID, no FK.
-- =====================================================================
CREATE TABLE metadata_fields (
    metadata_field_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    field_name VARCHAR(255) NOT NULL,
    data_type VARCHAR(30) NOT NULL,
    reference_list_id UUID,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_metadata_fields_tenant_name UNIQUE (tenant_id, field_name),
    CONSTRAINT ck_metadata_fields_data_type CHECK (data_type IN
        ('text', 'date', 'number', 'boolean', 'dropdown', 'reference'))
);

CREATE INDEX ix_metadata_fields_tenant_id ON metadata_fields (tenant_id);

-- =====================================================================
-- document_type_fields (5.3, partial standard fields: created_at only)
-- =====================================================================
CREATE TABLE document_type_fields (
    document_type_field_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_type_id UUID NOT NULL REFERENCES document_types (document_type_id),
    metadata_field_id UUID NOT NULL REFERENCES metadata_fields (metadata_field_id),
    mandatory BOOLEAN NOT NULL DEFAULT false,
    display_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_type_fields UNIQUE (document_type_id, metadata_field_id)
);

CREATE INDEX ix_document_type_fields_document_type_id ON document_type_fields (document_type_id);
CREATE INDEX ix_document_type_fields_metadata_field_id ON document_type_fields (metadata_field_id);
CREATE INDEX ix_document_type_fields_tenant_id ON document_type_fields (tenant_id);

-- =====================================================================
-- document_metadata_values (5.4, partial standard fields: created_at only)
-- =====================================================================
CREATE TABLE document_metadata_values (
    document_metadata_value_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    metadata_field_id UUID NOT NULL REFERENCES metadata_fields (metadata_field_id),
    value TEXT,
    value_text TEXT,
    value_date DATE,
    value_number NUMERIC,
    value_boolean BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_metadata_values UNIQUE (document_id, metadata_field_id)
);

CREATE INDEX ix_document_metadata_values_document_id ON document_metadata_values (document_id);
CREATE INDEX ix_document_metadata_values_metadata_field_id ON document_metadata_values (metadata_field_id);
CREATE INDEX ix_document_metadata_values_tenant_id ON document_metadata_values (tenant_id);
