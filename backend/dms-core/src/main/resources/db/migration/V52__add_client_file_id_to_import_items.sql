-- Module 2.1 - File Import/Export: retrying an upload without creating a
-- duplicate item (FIE-NFR-004).
--
-- The browser gives every file it queues an ID of its own and sends it with
-- the upload. When an upload fails without a response (network error,
-- timeout, 5xx) the browser can't tell whether the server took the file, so
-- a retry resends it with the same ID. If an item in the batch already has
-- that ID, the server returns that item's original outcome (accepted, or
-- the same rejection) and creates and stages nothing.
--
-- The partial unique index keeps one item per ID per batch. The batch lock
-- in BulkImportServiceImpl#receiveFile already serializes uploads into a
-- batch; the index is the backstop.
--
-- Conventions mirrored from V43/V46/V48: one ALTER TABLE per migration on
-- import_items. Nullable, so existing rows and uploads without an ID are
-- unaffected.

ALTER TABLE import_items
    ADD COLUMN client_file_id VARCHAR(64) NULL;

CREATE UNIQUE INDEX uq_import_items_batch_client_file_id
    ON import_items (import_batch_id, client_file_id)
    WHERE client_file_id IS NOT NULL;
