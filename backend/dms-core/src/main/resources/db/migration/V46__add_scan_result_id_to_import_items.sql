-- Module 2.1 - File Import/Export: record creation.
--
-- The import worker hands each passing row's staged file to the malware
-- scan gate, which answers with a scan result (the ingestion ticket). Its
-- id is saved on the row straight away, so a redelivered batch or the
-- follow-up job re-checks that ticket instead of submitting the file again.
-- NULL until the gate has accepted the row's file.
--
-- Conventions mirrored from V43/V44: bare FK column with an index on it.

ALTER TABLE import_items
    ADD COLUMN scan_result_id UUID NULL,
    ADD CONSTRAINT fk_import_items_scan_result FOREIGN KEY (scan_result_id)
        REFERENCES scan_results (scan_result_id);

CREATE INDEX ix_import_items_scan_result_id ON import_items (scan_result_id);
