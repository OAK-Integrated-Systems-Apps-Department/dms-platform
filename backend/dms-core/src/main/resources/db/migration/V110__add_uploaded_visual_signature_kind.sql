-- 1.4 Digital Signature Integration - "uploaded" (a photo/scan, with its
-- background made transparent client-side) becomes its own visual signature
-- kind, distinct from "drawn" (a canvas doodle). V109 originally stored both
-- under "drawn" since the backend only ever cared "is this an image or
-- text" - but that meant re-opening the signing dialog couldn't tell which
-- editor to default back to for an already-saved image, showing a saved
-- upload inside the hand-drawing canvas instead of the upload preview.
--
-- Both still store their PNG in the same image_png/visual_signature_image
-- column and are validated identically (anything other than "typed" needs
-- an image, nothing else changes) - this migration only widens the two kind
-- CHECK constraints to also accept 'uploaded'.

ALTER TABLE user_visual_signatures
    DROP CONSTRAINT ck_user_visual_signatures_kind,
    DROP CONSTRAINT ck_user_visual_signatures_payload,
    ADD CONSTRAINT ck_user_visual_signatures_kind CHECK (kind IN ('typed', 'drawn', 'uploaded')),
    ADD CONSTRAINT ck_user_visual_signatures_payload CHECK (
        (kind = 'typed' AND typed_text IS NOT NULL AND image_png IS NULL) OR
        (kind IN ('drawn', 'uploaded') AND image_png IS NOT NULL AND typed_text IS NULL));

ALTER TABLE document_signatures
    DROP CONSTRAINT ck_document_signatures_visual_kind,
    ADD CONSTRAINT ck_document_signatures_visual_kind CHECK (visual_signature_kind IS NULL
        OR visual_signature_kind IN ('typed', 'drawn', 'uploaded'));
