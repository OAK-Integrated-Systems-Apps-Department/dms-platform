-- Seeds metadata_field_label_aliases (V40) with ocr-worker's legacy,
-- hardcoded label vocabulary (_LABEL_ALIASES in
-- backend/workers/ocr-worker/src/ocr_worker/extraction.py, as it stood
-- when the worker moved onto configurable hints), so that move changes no
-- extraction result by itself.
--
-- Mirrors the old dict's lookup exactly: it was keyed by field_key alone,
-- so it applied to ANY tenant's field with that key - V30-seeded or
-- created by hand, whatever its status. Every row below is therefore a
-- global hint (document_type_id NULL), inserted for every non-deleted
-- metadata_fields row in every tenant whose field_key matches - driven off
-- "FROM metadata_fields" rather than a fixed tenant id, same approach as
-- V30. display_order is (index + 1) * 10 in the old list's own order,
-- which is the matcher's priority order (most specific first). created_by
-- is NULL: no user authored these.
--
-- One deliberate addition: "number", appended LAST to
-- purchase_order_number. The old matcher special-cased that field in code
-- - a bare "Number" label, whole-line only, tried only after every other
-- hint had missed (a real purchase order's "Number / Date / Currency /
-- Value" header box). That branch is deleted from the worker, which now
-- gives the same whole-line-only, tried-last treatment to any one-word
-- hint that is also the last word of another field's multi-word hint
-- ("number" vs "invoice number"/"reference number") - so this row
-- reproduces the old branch exactly, and no other seeded hint qualifies.
--
-- Idempotent: ON CONFLICT DO NOTHING against uq_mfla_field_scope_alias, so
-- a re-run inserts nothing and a hint a tenant already added by hand is
-- left untouched. ocr-worker's test suite parses the VALUES list below and
-- compares it with its own copy of this seed, so the two can never drift -
-- keep it one ('field_key', 'alias', display_order) tuple per line, with
-- no comments inside the list.
--
-- FLAGGED FOLLOW-UP (not fixed here) - tenant provisioning never seeds a
-- metadata catalog. TenantServiceImpl#createTenant and
-- TenantProvisioningService#provisionTenant only ever insert the tenants
-- row (plus, for provisioning, the first admin's users row); neither
-- inserts any metadata_fields, metadata_field_options, document_types or
-- document_type_fields. V30's starter catalogue - and so this migration's
-- hints, which hang off it - only reached tenants that already existed
-- when those migrations ran (V30's own header admits it: "a tenant
-- provisioned after this migration runs simply won't get the starter
-- catalogue"). Evidence: in the local dev database, 200 of 314 active
-- tenants have zero metadata fields - every one of them created after V30
-- ran on 2026-09-18 - so their users have no fields, no document types and
-- nothing for OCR to extract into. Not fixed here: it is a provisioning
-- change, not an OCR one, and a migration can only ever reach the tenants
-- that exist at the moment it runs - the same trap V30 fell into. The
-- platform tenant (SystemActor.PLATFORM_TENANT_ID, seeded by V24) holds
-- the full starter catalogue (V30) and these hints, which makes it the
-- natural template. A fix must copy, inside provisioning and idempotently,
-- the template's fields, their options and validations, its document types,
-- their current mappings AND their metadata_field_label_aliases rows -
-- remapping every field_id/document_type_id (and parent_option_id) onto
-- the new tenant's own rows, since every FK here is tenant-scoped.
INSERT INTO metadata_field_label_aliases (tenant_id, field_id, alias_text, display_order)
SELECT mf.tenant_id, mf.field_id, v.alias_text, v.display_order
FROM metadata_fields mf
JOIN (VALUES
    ('author', 'author', 10),
    ('author', 'prepared by', 20),
    ('author', 'written by', 30),
    ('author', 'authored by', 40),
    ('department', 'department', 10),
    ('department', 'dept', 20),
    ('department', 'division', 30),
    ('department', 'business unit', 40),
    ('reference_number', 'reference number', 10),
    ('reference_number', 'reference no', 20),
    ('reference_number', 'ref no', 30),
    ('reference_number', 'ref. no', 40),
    ('reference_number', 'ref#', 50),
    ('reference_number', 'ref #', 60),
    ('reference_number', 'reference', 70),
    ('classification_level', 'classification level', 10),
    ('classification_level', 'classification', 20),
    ('classification_level', 'sensitivity', 30),
    ('retention_class', 'retention class', 10),
    ('retention_class', 'retention schedule', 20),
    ('retention_class', 'retention', 30),
    ('effective_date', 'effective date', 10),
    ('effective_date', 'effective from', 20),
    ('effective_date', 'effective', 30),
    ('expiry_date', 'expiry date', 10),
    ('expiry_date', 'expiration date', 20),
    ('expiry_date', 'expires on', 30),
    ('expiry_date', 'valid until', 40),
    ('expiry_date', 'expires', 50),
    ('description', 'description', 10),
    ('description', 'summary', 20),
    ('tags', 'tags', 10),
    ('keywords', 'keywords', 10),
    ('keywords', 'key words', 20),
    ('invoice_number', 'invoice number', 10),
    ('invoice_number', 'invoice no', 20),
    ('invoice_number', 'inv no', 30),
    ('invoice_number', 'inv#', 40),
    ('invoice_number', 'inv #', 50),
    ('invoice_number', 'invoice#', 60),
    ('invoice_date', 'invoice date', 10),
    ('purchase_order_number', 'purchase order number', 10),
    ('purchase_order_number', 'po number', 20),
    ('purchase_order_number', 'po no', 30),
    ('purchase_order_number', 'po#', 40),
    ('purchase_order_number', 'order number', 50),
    ('purchase_order_number', 'number', 60),
    ('currency', 'currency', 10),
    ('vendor_supplier_name', 'vendor / supplier', 10),
    ('vendor_supplier_name', 'vendor/supplier', 20),
    ('vendor_supplier_name', 'vendor', 30),
    ('vendor_supplier_name', 'supplier', 40),
    ('vendor_supplier_name', 'billed by', 50),
    ('vendor_supplier_name', 'seller', 60),
    ('cost_centre', 'cost centre', 10),
    ('cost_centre', 'cost center', 20),
    ('amount', 'total net item value excl tax', 10),
    ('amount', 'net item value', 20),
    ('amount', 'total amount', 30),
    ('amount', 'total due', 40),
    ('amount', 'grand total', 50),
    ('amount', 'amount due', 60),
    ('amount', 'net amount due', 70),
    ('amount', 'amount', 80),
    ('amount', 'value', 90)
) AS v(field_key, alias_text, display_order)
    ON v.field_key = mf.field_key
WHERE mf.is_deleted = false
ON CONFLICT DO NOTHING;
