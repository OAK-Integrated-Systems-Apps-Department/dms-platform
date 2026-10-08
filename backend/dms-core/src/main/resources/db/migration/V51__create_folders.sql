-- Module DSC - Document Storage & Categorization: folders (Table Dictionary
-- 4.4, docs/database/V4) and documents.folder_id (4.1's column V32 left out).
--
-- One shared folder tree per tenant (team/department folders, DSC-FR-002):
-- parent_folder_id NULL = a top-level folder; documents with folder_id NULL
-- are "not in a folder". Names are unique among a folder's live children,
-- ignoring case (DSC-FR-009). Deleting is soft and only allowed for an empty
-- folder (FolderService), so a document can never point at a deleted one.
--
-- Deviations from the Table Dictionary, all additive:
--   - composite (tenant_id, X) FKs, as V39+, so a folder can only have a
--     parent - and hold documents - of its own tenant
--   - scan_results.folder_id: an upload's target folder, carried through the
--     malware scan like document_type_id/title until the document is created
--   - import_batches.folder_id: the folder a bulk import files its documents in
--   - docm.documents.move: moving documents between folders (no existing
--     DOCM code covers changing a document)

CREATE TABLE folders (
    folder_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenants (tenant_id),
    parent_folder_id UUID,
    folder_name      VARCHAR(255) NOT NULL,
    created_by       UUID REFERENCES users (user_id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       UUID REFERENCES users (user_id),
    updated_at       TIMESTAMPTZ,
    is_deleted       BOOLEAN NOT NULL DEFAULT false,
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_folders_tenant_folder UNIQUE (tenant_id, folder_id),
    CONSTRAINT fk_folders_parent FOREIGN KEY (tenant_id, parent_folder_id)
        REFERENCES folders (tenant_id, folder_id),
    CONSTRAINT ck_folders_not_own_parent CHECK (parent_folder_id IS NULL OR parent_folder_id <> folder_id),
    CONSTRAINT ck_folders_name CHECK (length(btrim(folder_name)) > 0)
);

CREATE INDEX ix_folders_tenant_id ON folders (tenant_id);
CREATE INDEX ix_folders_parent_folder_id ON folders (parent_folder_id);
-- DSC-FR-009. The all-zero UUID stands in for "top level" so siblings at the
-- root are covered too (NULLs never collide in a unique index).
CREATE UNIQUE INDEX uq_folders_live_sibling_name ON folders
    (tenant_id, COALESCE(parent_folder_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(folder_name))
    WHERE NOT is_deleted;

ALTER TABLE documents ADD COLUMN folder_id UUID;
ALTER TABLE documents ADD CONSTRAINT fk_documents_folder FOREIGN KEY (tenant_id, folder_id)
    REFERENCES folders (tenant_id, folder_id);
CREATE INDEX ix_documents_folder_id ON documents (folder_id);

ALTER TABLE scan_results ADD COLUMN folder_id UUID;
ALTER TABLE scan_results ADD CONSTRAINT fk_scan_results_folder FOREIGN KEY (tenant_id, folder_id)
    REFERENCES folders (tenant_id, folder_id);
CREATE INDEX ix_scan_results_folder_id ON scan_results (folder_id);

ALTER TABLE import_batches ADD COLUMN folder_id UUID;
ALTER TABLE import_batches ADD CONSTRAINT fk_import_batches_folder FOREIGN KEY (tenant_id, folder_id)
    REFERENCES folders (tenant_id, folder_id);
CREATE INDEX ix_import_batches_folder_id ON import_batches (folder_id);

-- docm.documents.move, following V33.
INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, 'MOVE', 'Move'
FROM permission_modules pm
WHERE pm.module_code = 'DOCM'
ON CONFLICT (module_id, action_code) DO NOTHING;

INSERT INTO permissions (permission_code, name, description, action_id)
SELECT 'docm.documents.move', 'Move documents between folders',
       'File documents into folders and move them from one folder to another.', pa.action_id
FROM permission_actions pa
JOIN permission_modules pm ON pm.module_id = pa.module_id AND pm.module_code = 'DOCM'
WHERE pa.action_code = 'MOVE'
ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING;
