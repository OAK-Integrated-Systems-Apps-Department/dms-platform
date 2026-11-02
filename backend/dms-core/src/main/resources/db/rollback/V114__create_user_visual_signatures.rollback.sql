-- Manual rollback ("down") for V109__create_user_visual_signatures.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it.

-- Restore the V108 version of the append-only guard function (without the
-- three visual_signature_* columns) before dropping those columns.
CREATE OR REPLACE FUNCTION document_signatures_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'document_signatures rows are append-only - % cannot be deleted', OLD.signature_id;
    END IF;
    IF NEW.signature_id IS DISTINCT FROM OLD.signature_id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.document_id IS DISTINCT FROM OLD.document_id
        OR NEW.version_id IS DISTINCT FROM OLD.version_id
        OR NEW.workflow_instance_id IS DISTINCT FROM OLD.workflow_instance_id
        OR NEW.task_id IS DISTINCT FROM OLD.task_id
        OR NEW.signer_user_id IS DISTINCT FROM OLD.signer_user_id
        OR NEW.signer_name IS DISTINCT FROM OLD.signer_name
        OR NEW.signer_email IS DISTINCT FROM OLD.signer_email
        OR NEW.meaning IS DISTINCT FROM OLD.meaning
        OR NEW.comment_text IS DISTINCT FROM OLD.comment_text
        OR NEW.manifest_text IS DISTINCT FROM OLD.manifest_text
        OR NEW.signature_der_base64 IS DISTINCT FROM OLD.signature_der_base64
        OR NEW.public_key_pem IS DISTINCT FROM OLD.public_key_pem
        OR NEW.signing_key_id IS DISTINCT FROM OLD.signing_key_id
        OR NEW.signing_key_name IS DISTINCT FROM OLD.signing_key_name
        OR NEW.algorithm IS DISTINCT FROM OLD.algorithm
        OR NEW.signed_at IS DISTINCT FROM OLD.signed_at
        OR NEW.created_by IS DISTINCT FROM OLD.created_by
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
    THEN
        RAISE EXCEPTION 'document_signatures evidence columns are immutable - % cannot be changed', OLD.signature_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

ALTER TABLE document_signatures
    DROP CONSTRAINT IF EXISTS ck_document_signatures_visual_kind,
    DROP COLUMN IF EXISTS visual_signature_image,
    DROP COLUMN IF EXISTS visual_signature_text,
    DROP COLUMN IF EXISTS visual_signature_kind;

DROP TABLE IF EXISTS user_visual_signatures;
