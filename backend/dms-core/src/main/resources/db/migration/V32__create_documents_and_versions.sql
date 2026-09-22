-- Documents & versions (docs/database/V4, subset) -- the minimal slice needed
-- to connect ocr-worker to the frontend: documents, document_versions.
-- document_types is NOT created here -- it's owned by module 2.7
-- (Metadata & Document-Type Management, V28/V30/V31), which landed after
-- this migration was first written as a placeholder that also created its
-- own document_types table. That placeholder table is gone; documents.
-- document_type_id below references the real one instead.
-- folders/document_relationships/tags/metadata_fields from the fuller V4
-- spec are intentionally omitted here -- nothing in this integration needs
-- them yet; add them in a later migration when the broader document
-- module is built out.
--
-- documents.latest_version_id and document_versions.document_id are
-- mutually NOT NULL foreign keys into each other (a document always has a
-- version; a version always belongs to a document). Unlike the original
-- docs/database/V4 spec (which works around this at the DDL level by adding
-- the three version FKs via a deferred ALTER TABLE, but leaves the
-- constraints themselves NOT DEFERRABLE), these are declared
-- DEFERRABLE INITIALLY DEFERRED so a normal JPA/Hibernate flow -- persist
-- the Document, then persist the DocumentVersion with that document's id,
-- both in one @Transactional method -- just works: the FK check runs at
-- COMMIT, by which point both rows exist. This is a deliberate, documented
-- deviation from the V4 spec's constraint timing, not a change to the
-- schema's logical shape.

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

ALTER TABLE documents
    ADD CONSTRAINT fk_documents_current_version FOREIGN KEY (current_version_id) REFERENCES document_versions (version_id) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_documents_latest_version FOREIGN KEY (latest_version_id) REFERENCES document_versions (version_id) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_documents_published_version FOREIGN KEY (published_version_id) REFERENCES document_versions (version_id) DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX ix_documents_current_version_id ON documents (current_version_id);
CREATE INDEX ix_documents_latest_version_id ON documents (latest_version_id);
CREATE INDEX ix_documents_published_version_id ON documents (published_version_id);
