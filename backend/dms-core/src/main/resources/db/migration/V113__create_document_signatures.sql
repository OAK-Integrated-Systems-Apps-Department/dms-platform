-- 1.4 Digital Signature Integration (module DSI) - INTERNAL signing only.
-- External signers, a third-party provider, qualified/PAdES signatures, bulk
-- signing and handwritten-signature images are explicitly out of scope - see
-- com.oak.dms.signature's package docs and docs/requirement-registry.md 1.4.
--
--   user_signing_keys     one ECDSA P-256 key pair per user, created on first
--                         signing; "generation" increments on a revoke so a
--                         new key never reuses an old Transit/file key name.
--   document_signatures   append-only evidence rows - a signature is bound to
--                         ONE document version forever and is never moved or
--                         rewritten; only its status/verification columns may
--                         ever change (see the guard trigger below).
--   workflow_stages.requires_signature  per-stage flag (mirrors V95's
--                         allow_info_requests): such a stage's plain
--                         /approve is refused (409 WORKFLOW_SIGNATURE_REQUIRED)
--                         and must go through /sign-and-approve instead.
--   documents.signature_locked_at/signature_locked_version_id  set once a
--                         workflow with at least one valid signature is
--                         approved - see DocumentSignatureLockService.
--
-- Numbered V108, not V105: merging in Feat/bulk-import-export brought its own
-- V103-V105 (notification core/config/permissions, PR #34) - this migration
-- and the two colliding watermark ones (V106 tenant_watermark_settings, V107
-- bulk-export-watermark) were renumbered past them to resolve the collision.

-- =====================================================================
-- user_signing_keys
-- =====================================================================
CREATE TABLE user_signing_keys (
    key_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    -- Deliberately RESTRICT (no ON DELETE clause, Postgres's default): a user
    -- who has ever held a signing key can be deactivated but never
    -- permanently purged while this row (or, transitively, any signature it
    -- made) exists - the identity behind a legal signature is never erased.
    -- Same reasoning applies to document_signatures.signer_user_id/created_by below.
    user_id UUID NOT NULL REFERENCES users (user_id),
    -- Increments each time the user's active key is revoked, so a new key
    -- never reuses dms-sig-<tenant>-<user>-<generation>.
    generation INT NOT NULL DEFAULT 1,
    key_name VARCHAR(255) NOT NULL,
    public_key_pem TEXT NOT NULL,
    algorithm VARCHAR(40) NOT NULL DEFAULT 'ECDSA_P256_SHA256',
    backend VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    -- created_by/revoked_by: who performed the action, not whose key this is
    -- (that's user_id, deliberately left RESTRICT below - see the table
    -- comment) - audit-trail columns, same ON DELETE SET NULL convention
    -- V14 established for this shape of column everywhere else.
    created_by UUID REFERENCES users (user_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_by UUID REFERENCES users (user_id) ON DELETE SET NULL,
    revoked_at TIMESTAMPTZ,
    revoke_reason VARCHAR(2000),
    CONSTRAINT uq_user_signing_keys_key_name UNIQUE (key_name),
    CONSTRAINT ck_user_signing_keys_backend CHECK (backend IN ('transit', 'local-dev')),
    CONSTRAINT ck_user_signing_keys_status CHECK (status IN ('active', 'revoked')),
    CONSTRAINT ck_user_signing_keys_revoked CHECK (status = 'active' OR revoked_at IS NOT NULL)
);

CREATE INDEX ix_user_signing_keys_tenant_user ON user_signing_keys (tenant_id, user_id);
-- Revoking a key never invalidates signatures it made earlier (decision 9) -
-- this only ever stops a NEW signature from being made with a revoked key.
CREATE UNIQUE INDEX uq_user_signing_keys_one_active
    ON user_signing_keys (tenant_id, user_id) WHERE status = 'active';

-- =====================================================================
-- document_signatures
-- =====================================================================
CREATE TABLE document_signatures (
    signature_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id UUID NOT NULL,
    version_id UUID NOT NULL,
    workflow_instance_id UUID NOT NULL,
    -- Nullable in the schema (a future non-task signing path), but every
    -- signature produced by sign-and-approve has one - see the partial
    -- unique index below, which is what makes that endpoint idempotent
    -- per task.
    task_id UUID,
    signer_user_id UUID NOT NULL REFERENCES users (user_id),
    -- Frozen at signing time (also embedded verbatim in manifest_text) -
    -- never re-derived from the live user row, which can change or be
    -- deleted after the fact.
    signer_name VARCHAR(255) NOT NULL,
    signer_email VARCHAR(255) NOT NULL,
    meaning VARCHAR(20) NOT NULL,
    comment_text VARCHAR(2000),
    -- The exact canonical JSON text that was signed - verification replays
    -- THIS text, never a re-serialization of it (decision 3).
    manifest_text TEXT NOT NULL,
    signature_der_base64 TEXT NOT NULL,
    -- Stored per row (not just on user_signing_keys) so evidence is
    -- verifiable offline, and remains correct if the key is later revoked.
    public_key_pem TEXT NOT NULL,
    signing_key_id UUID NOT NULL REFERENCES user_signing_keys (key_id),
    signing_key_name VARCHAR(255) NOT NULL,
    algorithm VARCHAR(40) NOT NULL DEFAULT 'ECDSA_P256_SHA256',
    -- Mutable evidence: only these five columns may ever change after
    -- insert (the guard trigger enforces this) - the signature itself
    -- never does.
    status VARCHAR(20) NOT NULL DEFAULT 'valid',
    status_changed_at TIMESTAMPTZ,
    status_reason VARCHAR(2000),
    last_verified_at TIMESTAMPTZ,
    last_verification_result VARCHAR(30),
    signed_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_document_signatures_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id),
    CONSTRAINT fk_document_signatures_version FOREIGN KEY (tenant_id, version_id)
        REFERENCES document_versions (tenant_id, version_id),
    CONSTRAINT fk_document_signatures_instance FOREIGN KEY (tenant_id, workflow_instance_id)
        REFERENCES workflow_instances (tenant_id, instance_id),
    CONSTRAINT fk_document_signatures_task FOREIGN KEY (tenant_id, task_id)
        REFERENCES workflow_tasks (tenant_id, task_id),
    CONSTRAINT ck_document_signatures_meaning CHECK (meaning IN
        ('approved', 'reviewed', 'authored', 'acknowledged')),
    CONSTRAINT ck_document_signatures_status CHECK (status IN ('valid', 'invalidated')),
    CONSTRAINT ck_document_signatures_status_reason CHECK (status = 'valid' OR status_reason IS NOT NULL),
    CONSTRAINT ck_document_signatures_verification_result CHECK (last_verification_result IS NULL
        OR last_verification_result IN ('valid', 'signature_mismatch', 'document_altered', 'unreadable'))
);

-- One signature per workflow task - makes /sign-and-approve idempotent: a
-- retried call against a task that already produced a signature fails the
-- insert rather than producing a second one.
CREATE UNIQUE INDEX uq_document_signatures_one_per_task
    ON document_signatures (tenant_id, task_id) WHERE task_id IS NOT NULL;
CREATE INDEX ix_document_signatures_document ON document_signatures (tenant_id, document_id);
CREATE INDEX ix_document_signatures_version ON document_signatures (tenant_id, version_id);
CREATE INDEX ix_document_signatures_instance ON document_signatures (tenant_id, workflow_instance_id);

-- =====================================================================
-- Append-only guard: no DELETE, no UPDATE of an evidence column. Only
-- status/status_changed_at/status_reason/last_verified_at/
-- last_verification_result may change (decision 9).
-- =====================================================================
CREATE FUNCTION document_signatures_append_only() RETURNS trigger AS $$
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

CREATE TRIGGER trg_document_signatures_append_only
    BEFORE UPDATE OR DELETE ON document_signatures
    FOR EACH ROW EXECUTE FUNCTION document_signatures_append_only();

-- Belt and braces, same convention as workflow_actions (V93) and
-- audit_events (V23): a non-owning, non-superuser application role is also
-- blocked at the grant level, not just by the trigger.
REVOKE UPDATE, DELETE ON document_signatures FROM PUBLIC;

-- =====================================================================
-- workflow_stages.requires_signature - mirrors allow_info_requests (V47)
-- =====================================================================
ALTER TABLE workflow_stages ADD COLUMN requires_signature BOOLEAN NOT NULL DEFAULT false;

-- =====================================================================
-- documents.signature_locked_at / signature_locked_version_id
-- =====================================================================
ALTER TABLE documents
    ADD COLUMN signature_locked_at TIMESTAMPTZ,
    ADD COLUMN signature_locked_version_id UUID REFERENCES document_versions (version_id),
    ADD CONSTRAINT ck_documents_signature_lock CHECK (
        (signature_locked_at IS NULL) = (signature_locked_version_id IS NULL));

-- =====================================================================
-- Permission: dsi.unlock-signed-documents.unlock (V98 pattern - module DSI
-- already exists, seeded by V7/V10; this only adds the one new action+permission).
-- =====================================================================
INSERT INTO permission_actions (module_id, action_code, action_name)
SELECT pm.module_id, 'UNLOCK_SIGNED_DOCUMENTS', 'Unlock Signed Documents'
FROM permission_modules pm
WHERE pm.module_code = 'DSI'
ON CONFLICT (module_id, action_code) DO NOTHING;

DO
$$
DECLARE
    n bigint;
BEGIN
    WITH inserted AS (
        INSERT INTO permissions (permission_code, name, action_id)
        SELECT v.permission_code, v.name, pa.action_id
        FROM (VALUES
            ('dsi.unlock-signed-documents.unlock', 'Unlock a signature-locked document', 'UNLOCK_SIGNED_DOCUMENTS', 'DSI')
        ) AS v(permission_code, name, action_code, module_code)
        JOIN permission_modules pm ON pm.module_code = v.module_code
        JOIN permission_actions pa ON pa.module_id = pm.module_id AND pa.action_code = v.action_code
        ON CONFLICT (permission_code) WHERE tenant_id IS NULL DO NOTHING
        RETURNING 1)
    SELECT count(*) INTO n FROM inserted;
    RAISE NOTICE 'V108: % digital-signature permission(s) seeded (granted to no role)', n;
END;
$$;
