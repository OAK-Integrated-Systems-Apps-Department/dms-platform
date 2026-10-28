-- Folder access control (Module DSC, "Configure Folder Access Control").
--
-- Each folder either inherits its access from its parent (the default for
-- every folder, so existing folders keep behaving as today), is open to
-- everyone in the tenant, or is restricted to the users and roles listed
-- in folder_access. A top-level folder that inherits is open to everyone.
-- A folder's access covers its documents and every subfolder that inherits.
-- Fine-grained permission codes still apply on top: access decides WHICH
-- folders someone can work in, permissions decide WHAT they can do there.

ALTER TABLE folders
    ADD COLUMN access_mode VARCHAR(20) NOT NULL DEFAULT 'inherit',
    ADD CONSTRAINT ck_folders_access_mode CHECK (access_mode IN ('inherit', 'everyone', 'restricted'));

CREATE TABLE folder_access (
    folder_access_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL,
    folder_id        UUID        NOT NULL,
    -- A user (users.user_id) or a role (roles.role_id) of the same tenant.
    -- Not a foreign key because it points at one of two tables; entries for
    -- a deleted user or role simply stop matching anyone.
    principal_type   VARCHAR(10) NOT NULL,
    principal_id     UUID        NOT NULL,
    -- view: browse and download; edit: also upload, move documents, create
    -- and rename subfolders; manage: also move/delete the folder and change
    -- its access.
    access_level     VARCHAR(10) NOT NULL,
    created_by       UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_folder_access_folder FOREIGN KEY (tenant_id, folder_id)
        REFERENCES folders (tenant_id, folder_id) ON DELETE CASCADE,
    CONSTRAINT ck_folder_access_principal_type CHECK (principal_type IN ('user', 'role')),
    CONSTRAINT ck_folder_access_level CHECK (access_level IN ('view', 'edit', 'manage')),
    CONSTRAINT uq_folder_access_principal UNIQUE (folder_id, principal_type, principal_id)
);

-- "Which folders can this user (or these roles) get into?" - asked on every browse.
CREATE INDEX ix_folder_access_tenant_principal ON folder_access (tenant_id, principal_type, principal_id);
