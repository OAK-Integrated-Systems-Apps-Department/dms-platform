-- One master key per tenant (encryption at rest, V55).
--
-- Every tenant's objects are wrapped with that tenant's own master key
-- (OpenBao Transit key "dms-documents-<tenantId>", or its own entry in the
-- development key file), so a tenant admin rotating or re-wrapping keys
-- touches only their own organisation's data, and one tenant's key never
-- opens another tenant's documents. "platform" is kept for objects written
-- outside any tenant (none today).
--
-- tenant_id: whose object it is. key_scope: which master key wraps it - the
-- tenant id, or 'platform'. Rows from before this migration were all wrapped
-- with the single platform key; their tenant comes from the document
-- version they belong to, and "re-wrap" moves them onto their tenant's key.

ALTER TABLE storage_object_keys
    ADD COLUMN tenant_id UUID REFERENCES tenants (tenant_id),
    ADD COLUMN key_scope VARCHAR(64) NOT NULL DEFAULT 'platform';

UPDATE storage_object_keys k
SET tenant_id = v.tenant_id
FROM document_versions v
WHERE v.storage_location_id = k.storage_location_id
  AND v.storage_object_key = k.object_key;

-- A tenant's own key screen: its rows by key, version.
CREATE INDEX ix_storage_object_keys_tenant ON storage_object_keys (tenant_id, key_provider, key_scope, key_version);
