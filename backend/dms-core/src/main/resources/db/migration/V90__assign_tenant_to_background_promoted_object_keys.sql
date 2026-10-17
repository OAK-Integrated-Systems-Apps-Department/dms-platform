-- Large uploads promoted in the background were encrypted under the PLATFORM master key.
--
-- A file handed off by the upload request (over one clamd segment, or a large ZIP) was
-- scanned and copied into the document location on a background thread with no tenant
-- bound, so EncryptingObjectStorageService wrapped its data key with the platform key and
-- recorded the key row with tenant_id null. Fixed in ScanWorkPool (it now binds the scan
-- row's tenant). Those files still decrypt - the row records its scope - but shredding the
-- tenant's key would leave them readable, and the tenant's re-wrap job never sees them,
-- because it only picks up rows with its own tenant_id.
--
-- This gives each such row the tenant of the document version (or rendition, or
-- quarantine record) stored at that object. The key scope stays 'platform', so the row is
-- now "another scope" to EncryptionAdminService's re-wrap job, which unwraps it with the
-- platform key and wraps it with the tenant's own - run "Re-wrap" on the tenant's
-- Encryption page (or wait for its scheduled rotation) to finish moving them. Rows that
-- match none of those are left as they are.

update storage_object_keys k
   set tenant_id = owner.tenant_id
  from (
        select dv.storage_location_id, dv.storage_object_key as object_key, dv.tenant_id
          from document_versions dv
        union
        select dv.storage_location_id, dv.rendition_object_key, dv.tenant_id
          from document_versions dv
         where dv.rendition_object_key is not null
        union
        select q.quarantine_location_id, q.quarantine_object_key, q.tenant_id
          from quarantine_records q
         where q.quarantine_object_key is not null
       ) owner
 where k.tenant_id is null
   and k.key_scope = 'platform'
   and k.storage_location_id = owner.storage_location_id
   and k.object_key = owner.object_key;
