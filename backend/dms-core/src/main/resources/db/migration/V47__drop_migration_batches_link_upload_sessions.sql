-- Module 2.1 - File Import/Export: one bulk import implementation.
--
-- V42 created migration_batches / migration_items for a second, parallel
-- template import. Bulk import is import_batches / import_items (V43-V46);
-- its CSV templates, ZIP intake and resumable uploads now live there, so
-- the V42 import tables are dropped. Neither was ever written to outside
-- development.
--
-- upload_sessions (also V42) stays: resumable uploads of a batch's ZIP.
-- import_items.upload_session_id gets the FK V43 left as a TODO until
-- upload_sessions existed (bare FK column + index, as V43).

DROP TABLE IF EXISTS migration_items;
DROP TABLE IF EXISTS migration_batches;

ALTER TABLE import_items
    ADD CONSTRAINT fk_import_items_upload_session FOREIGN KEY (upload_session_id)
        REFERENCES upload_sessions (upload_session_id);
