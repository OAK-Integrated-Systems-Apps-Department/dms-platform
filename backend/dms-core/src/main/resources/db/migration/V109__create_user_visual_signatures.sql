-- 1.4 Digital Signature Integration - a cosmetic VISUAL mark (typed name in a
-- script font, or hand-drawn on a canvas) shown alongside the real
-- cryptographic signature (V108), never a replacement for it. This is purely
-- decorative evidence of *how the signer chose to represent their name* -
-- the password/TOTP step-up, the ECDSA signature and its manifest remain the
-- only thing that is actually verified; nothing here is covered by that
-- signature or checked by SignatureVerifier.
--
--   user_visual_signatures  one saved mark per user, reused (and editable)
--                           on every future approval - NOT append-only like
--                           document_signatures: a person's visual signature
--                           is their own changeable preference, not evidence.
--   document_signatures.visual_signature_*  a snapshot of what was shown at
--                           the moment of THIS signature, copied in at
--                           insert time so a later profile change never
--                           rewrites history - added to the existing
--                           append-only guard trigger alongside it.

-- =====================================================================
-- user_visual_signatures
-- =====================================================================
CREATE TABLE user_visual_signatures (
    visual_signature_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    user_id UUID NOT NULL REFERENCES users (user_id),
    kind VARCHAR(10) NOT NULL,
    -- Rendered in a script/cursive font client-side - plain text, never markup.
    typed_text VARCHAR(150),
    -- A small PNG captured from the signing canvas - capped in the service
    -- layer (SIGNATURE_VISUAL_MAX_IMAGE_BYTES) well under this column's
    -- practical size, since this is a short name-sized doodle, not a photo.
    image_png BYTEA,
    created_by UUID NOT NULL REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID NOT NULL REFERENCES users (user_id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_visual_signatures_one_per_user UNIQUE (tenant_id, user_id),
    CONSTRAINT ck_user_visual_signatures_kind CHECK (kind IN ('typed', 'drawn')),
    CONSTRAINT ck_user_visual_signatures_payload CHECK (
        (kind = 'typed' AND typed_text IS NOT NULL AND image_png IS NULL) OR
        (kind = 'drawn' AND image_png IS NOT NULL AND typed_text IS NULL))
);

-- =====================================================================
-- document_signatures.visual_signature_* - snapshot taken at signing time
-- =====================================================================
ALTER TABLE document_signatures
    ADD COLUMN visual_signature_kind VARCHAR(10),
    ADD COLUMN visual_signature_text VARCHAR(150),
    ADD COLUMN visual_signature_image BYTEA,
    ADD CONSTRAINT ck_document_signatures_visual_kind CHECK (visual_signature_kind IS NULL
        OR visual_signature_kind IN ('typed', 'drawn'));

-- Re-create the append-only guard to also cover the three new columns -
-- same function, same trigger, just a wider immutability check.
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
        OR NEW.visual_signature_kind IS DISTINCT FROM OLD.visual_signature_kind
        OR NEW.visual_signature_text IS DISTINCT FROM OLD.visual_signature_text
        OR NEW.visual_signature_image IS DISTINCT FROM OLD.visual_signature_image
    THEN
        RAISE EXCEPTION 'document_signatures evidence columns are immutable - % cannot be changed', OLD.signature_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =====================================================================
-- Audit action: dsi module already exists (seeded V7/V10) - no new
-- permission needed, this is self-service (a user's own visual signature).
-- =====================================================================
