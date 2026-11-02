-- Manual rollback ("down") for V108__create_document_signatures.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it.

DO
$$
DECLARE
    deleted_permission_id UUID;
BEGIN
    SELECT permission_id INTO deleted_permission_id FROM permissions
        WHERE permission_code = 'dsi.unlock-signed-documents.unlock' AND tenant_id IS NULL;
    IF deleted_permission_id IS NOT NULL THEN
        DELETE FROM role_permissions WHERE permission_id = deleted_permission_id;
        DELETE FROM permissions WHERE permission_id = deleted_permission_id;
    END IF;
    DELETE FROM permission_actions pa
        USING permission_modules pm
        WHERE pa.module_id = pm.module_id AND pm.module_code = 'DSI' AND pa.action_code = 'UNLOCK_SIGNED_DOCUMENTS';
END;
$$;

ALTER TABLE documents
    DROP CONSTRAINT IF EXISTS ck_documents_signature_lock,
    DROP COLUMN IF EXISTS signature_locked_version_id,
    DROP COLUMN IF EXISTS signature_locked_at;

ALTER TABLE workflow_stages DROP COLUMN IF EXISTS requires_signature;

DROP TRIGGER IF EXISTS trg_document_signatures_append_only ON document_signatures;
DROP FUNCTION IF EXISTS document_signatures_append_only();

DROP TABLE IF EXISTS document_signatures;
DROP TABLE IF EXISTS user_signing_keys;
