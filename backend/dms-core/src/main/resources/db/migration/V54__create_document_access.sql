-- Document access control - the same idea as folder access (V53), per document.
--
-- A document either follows its folder (the default, so every existing
-- document keeps behaving as today; a top-level document is open to
-- everyone) or is restricted to the users and roles listed in
-- document_access. A restricted document uses only its own list - like
-- SharePoint's "unique permissions" - and tenant admins always have access.
-- Managing it uses the dsc.configure-folder-access-control.* codes.

ALTER TABLE documents
    ADD COLUMN access_mode VARCHAR(20) NOT NULL DEFAULT 'inherit',
    ADD CONSTRAINT ck_documents_access_mode CHECK (access_mode IN ('inherit', 'restricted'));

CREATE TABLE document_access (
    document_access_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID        NOT NULL,
    document_id        UUID        NOT NULL,
    -- A user (users.user_id) or a role (roles.role_id) of the same tenant;
    -- not a foreign key for the same reason as folder_access.principal_id.
    principal_type     VARCHAR(10) NOT NULL,
    principal_id       UUID        NOT NULL,
    -- view: open and download; edit: also move and delete; manage: also change access.
    access_level       VARCHAR(10) NOT NULL,
    created_by         UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_document_access_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id) ON DELETE CASCADE,
    CONSTRAINT ck_document_access_principal_type CHECK (principal_type IN ('user', 'role')),
    CONSTRAINT ck_document_access_level CHECK (access_level IN ('view', 'edit', 'manage')),
    CONSTRAINT uq_document_access_principal UNIQUE (document_id, principal_type, principal_id)
);

CREATE INDEX ix_document_access_tenant_principal ON document_access (tenant_id, principal_type, principal_id);

-- "Which documents are restricted?" - asked when listing for someone who isn't an admin.
CREATE INDEX ix_documents_tenant_restricted ON documents (tenant_id) WHERE access_mode = 'restricted' AND NOT is_deleted;
