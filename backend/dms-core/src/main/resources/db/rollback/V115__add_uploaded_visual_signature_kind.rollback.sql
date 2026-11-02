-- Manual rollback ("down") for V115__add_uploaded_visual_signature_kind.sql.
--
-- Outside classpath:db/migration, so Flyway never runs it. Converts every
-- 'uploaded' row back to 'drawn' before narrowing the constraints again -
-- the narrower CHECK would otherwise reject them outright.

UPDATE user_visual_signatures SET kind = 'drawn' WHERE kind = 'uploaded';
UPDATE document_signatures SET visual_signature_kind = 'drawn' WHERE visual_signature_kind = 'uploaded';

ALTER TABLE document_signatures
    DROP CONSTRAINT IF EXISTS ck_document_signatures_visual_kind,
    ADD CONSTRAINT ck_document_signatures_visual_kind CHECK (visual_signature_kind IS NULL
        OR visual_signature_kind IN ('typed', 'drawn'));

ALTER TABLE user_visual_signatures
    DROP CONSTRAINT IF EXISTS ck_user_visual_signatures_payload,
    DROP CONSTRAINT IF EXISTS ck_user_visual_signatures_kind,
    ADD CONSTRAINT ck_user_visual_signatures_kind CHECK (kind IN ('typed', 'drawn')),
    ADD CONSTRAINT ck_user_visual_signatures_payload CHECK (
        (kind = 'typed' AND typed_text IS NOT NULL AND image_png IS NULL) OR
        (kind = 'drawn' AND image_png IS NOT NULL AND typed_text IS NULL));
