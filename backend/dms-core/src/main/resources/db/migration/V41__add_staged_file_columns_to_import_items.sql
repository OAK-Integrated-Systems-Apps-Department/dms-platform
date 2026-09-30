-- Module 2.1 - File Import/Export: upload intake.
--
-- Each bulk-import file is uploaded on its own request, matched to an
-- import_items row by filename, and staged in object storage until the
-- import worker turns it into a document. These columns record where the
-- staged object lives; all three stay NULL until a file is received.
--
-- Conventions mirrored from V39: bare FK column with an index on it.

ALTER TABLE import_items
    ADD COLUMN staged_object_key          VARCHAR(1000) NULL,
    ADD COLUMN staged_storage_location_id UUID NULL,
    ADD COLUMN staged_size_bytes          BIGINT NULL,
    ADD CONSTRAINT fk_import_items_staged_storage_location FOREIGN KEY (staged_storage_location_id)
        REFERENCES storage_locations (storage_location_id);

CREATE INDEX ix_import_items_staged_storage_location_id ON import_items (staged_storage_location_id);
