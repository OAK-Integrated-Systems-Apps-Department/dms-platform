-- Minimal fix for document_versions.storage_location_id (added by the
-- pluggable-storage feature's V32, merged into this branch from develop
-- purely to resolve a migration-numbering collision with this branch's own
-- V32-V35 -- see V35/V36/V37's own history) having no corresponding
-- Java-side support here: this branch's ObjectStorageService is a single,
-- non-pluggable MinIO-backed implementation (no StorageLocation entity,
-- ObjectStorageServiceFactory, or StorageLocationResolver exists in this
-- branch's Java code, unlike the feature V32 came from), so nothing ever
-- created a storage_locations row for a new document_versions row to point
-- at, and every real upload failed on this column's NOT NULL constraint.
--
-- Seeds exactly one fixed, well-known row (id matches
-- DocumentServiceImpl#PLATFORM_DEFAULT_STORAGE_LOCATION_ID, same
-- all-zeros-plus-suffix convention as SystemActor's SYSTEM_USER_ID) that
-- every document_versions row on this branch points at unconditionally.
-- provider/bucket below are placeholders to satisfy this table's own CHECK
-- constraints -- this branch's ObjectStorageService never reads this row
-- back, unlike the pluggable-storage branch's adapters. Not a real fix for
-- the underlying architecture gap (this branch adopting pluggable storage,
-- or not, is a separate decision for when the branches are reconciled) --
-- just enough for uploads to work again in the meantime.
INSERT INTO storage_locations (storage_location_id, tenant_id, name, provider, bucket, status)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    NULL,
    'Platform default (OCR-Scanning branch placeholder - see V39)',
    'S3',
    'oakdms-documents',
    'active'
);
