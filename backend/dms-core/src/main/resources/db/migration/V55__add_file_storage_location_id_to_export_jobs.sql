-- Module 2.1 - File Import/Export: the storage location a bulk export's
-- ZIP was written to (the tenant's staging location at the time). Download
-- and expiry cleanup read and delete the file at exactly this location, so
-- a later change of the tenant's staging location can't strand the file.
-- The same reason import_items keeps staged_storage_location_id (V43).
--
-- One ALTER TABLE per migration, as V43/V46/V48. Nullable: set only once
-- the ZIP exists, and cleared again when it is deleted at expiry.

ALTER TABLE export_jobs
    ADD COLUMN file_storage_location_id UUID NULL REFERENCES storage_locations (storage_location_id);
