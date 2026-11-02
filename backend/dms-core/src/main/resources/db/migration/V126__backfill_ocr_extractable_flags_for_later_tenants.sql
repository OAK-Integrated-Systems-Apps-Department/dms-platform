-- V80 set `metadata_fields.is_ocr_extractable` for every tenant that existed when it ran
-- (2026-10-26 10:23:41). TenantStarterCatalogService.install() -- which seeds a NEW tenant's
-- copy of these same fields -- never set the flag itself, so any tenant provisioned after V80
-- got every OCR-relevant field defaulted to false: OCR would run to completion but report
-- every requested field "not_extractable", with nothing ever reaching the review screen.
--
-- The installer now sets it correctly (StarterCatalog.FieldDef.ocrExtractable), so this is a
-- one-time catch-up for tenants already provisioned before that fix, using the exact same
-- field lists V80 used.

UPDATE metadata_fields SET is_ocr_extractable = true
WHERE is_deleted = false AND field_key IN (
    'invoice_number', 'invoice_date', 'amount', 'currency',
    'vendor_supplier_name', 'purchase_order_number',
    'contract_party', 'contract_value', 'contract_start_date', 'contract_end_date',
    'effective_date', 'expiry_date', 'review_due_date', 'reference_number',
    'author', 'employee_id',
    'language'
);
