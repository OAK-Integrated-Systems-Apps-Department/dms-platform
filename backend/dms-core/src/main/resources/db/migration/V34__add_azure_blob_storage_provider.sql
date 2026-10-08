-- Adds AZURE_BLOB as a valid storage_locations.provider value, for
-- com.oak.dms.storage.adapter.AzureBlobStorageAdapter (see
-- com.oak.dms.storage.enums.StorageProvider and docs/storage-architecture.md).
-- No new columns needed: AZURE_BLOB reuses existing columns - endpoint (the
-- Blob Service endpoint URL), bucket (the blob container name), and
-- credential_ref (resolves STORAGE_CRED_<ref>_ACCOUNT_NAME/_ACCOUNT_KEY at
-- call time) - the same way S3 already reuses them for its own connection
-- details.
ALTER TABLE storage_locations DROP CONSTRAINT ck_storage_locations_provider;
ALTER TABLE storage_locations
    ADD CONSTRAINT ck_storage_locations_provider CHECK (provider IN ('S3', 'FILE_SYSTEM', 'AZURE_BLOB'));
