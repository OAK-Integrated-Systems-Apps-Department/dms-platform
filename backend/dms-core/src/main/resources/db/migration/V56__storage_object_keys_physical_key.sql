-- Encrypting documents stored before encryption at rest (V55) - without
-- touching document_versions, whose storage key is immutable by design
-- (document_versions_content_immutable).
--
-- The encrypted bytes are written next to the original as a new object, and
-- this row then names that object as where the bytes physically live while
-- keeping the logical key the version points at. Saving the row is the
-- atomic switch: before it, readers get the original; after it, the
-- encrypted copy - and only then is the readable original deleted. A crash at
-- any point leaves either the old readable object or the finished encrypted
-- one, never a half-switched document.
--
-- NULL (every object encrypted as it was uploaded) = the bytes live at object_key itself.

ALTER TABLE storage_object_keys
    ADD COLUMN physical_object_key VARCHAR(1000),
    ADD CONSTRAINT ck_storage_object_keys_physical CHECK (physical_object_key IS NULL OR physical_object_key <> object_key);
