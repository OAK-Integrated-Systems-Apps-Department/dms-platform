-- Module 2.7 - starter metadata field catalogue, dropdown options, and four
-- starter document types with schema_version = 1 field mappings.
--
-- No demo tenant exists anywhere in this codebase (tenants are created at
-- runtime only, never seeded by Flyway - confirmed by exhaustive search
-- before writing this migration). Every INSERT below is therefore driven
-- off "FROM tenants t" / "FROM document_types dt" rather than a fixed
-- tenant id, so it seeds the starter catalogue for whichever tenant(s)
-- already exist at migration time, and is idempotent per tenant via
-- ON CONFLICT / NOT EXISTS guards - a tenant provisioned after this
-- migration runs simply won't get the starter catalogue, same as how the
-- permissions catalog already behaves for tenant-specific data.

-- =====================================================================
-- metadata_fields (25 starter fields)
-- =====================================================================
INSERT INTO metadata_fields (tenant_id, field_key, label, help_text, data_type, is_system_field, is_searchable, status)
SELECT t.tenant_id, v.field_key, v.label, v.help_text, v.data_type, v.is_system_field, true, 'active'
FROM tenants t
CROSS JOIN (VALUES
    ('classification_level',   'Classification Level',    'Sensitivity classification of the document.',            'dropdown',     true),
    ('department',             'Department',               'Owning department or business unit.',                    'lookup',       false),
    ('tags',                   'Tags',                      'Free-form labels for grouping and discovery.',            'multi_select', false),
    ('retention_class',        'Retention Class',           'Retention schedule this document falls under.',          'dropdown',     true),
    ('description',            'Description',                'Free-text summary of the document.',                    'long_text',    false),
    ('author',                 'Author',                      'Person or team who authored the document.',             'text',         false),
    ('keywords',               'Keywords',                     'Search keywords beyond the free-text tags.',           'multi_select', false),
    ('language',               'Language',                      'Primary language of the document content.',          'dropdown',     false),
    ('reference_number',       'Reference Number',               'External or internal reference identifier.',       'text',         false),
    ('effective_date',         'Effective Date',                  'Date the document takes effect.',                 'date',         false),
    ('expiry_date',            'Expiry Date',                      'Date the document expires.',                     'date',         false),
    ('review_due_date',        'Review Due Date',                  'Date the document is next due for review.',    'date',         false),
    ('invoice_number',         'Invoice Number',                    'Vendor-issued invoice number.',               'text',         false),
    ('invoice_date',           'Invoice Date',                       'Date the invoice was issued.',               'date',         false),
    ('amount',                 'Amount',                               'Monetary amount on the document.',        'decimal',      false),
    ('currency',               'Currency',                              'Currency the amount is denominated in.', 'dropdown',     false),
    ('vendor_supplier_name',   'Vendor / Supplier Name',                 'Name of the vendor or supplier.',       'text',         false),
    ('purchase_order_number',  'Purchase Order Number',                   'Related purchase order number.',      'text',         false),
    ('contract_party',         'Contract Party',                           'Counterparty named on the contract.', 'text',         false),
    ('contract_value',         'Contract Value',                            'Total monetary value of the contract.', 'decimal',   false),
    ('contract_start_date',    'Contract Start Date',                        'Date the contract starts.',         'date',         false),
    ('contract_end_date',      'Contract End Date',                           'Date the contract ends.',          'date',         false),
    ('employee_id',            'Employee ID',                                  'Related employee identifier.',   'text',         false),
    ('project_code',           'Project Code',                                  'Project this document belongs to.', 'dropdown', false),
    ('cost_centre',            'Cost Centre',                                    'Cost centre to charge.',        'dropdown',     false)
) AS v(field_key, label, help_text, data_type, is_system_field)
ON CONFLICT (tenant_id, field_key) WHERE is_deleted = false DO NOTHING;

-- =====================================================================
-- metadata_field_options - classification_level, currency, retention_class.
-- language is deliberately left with zero seeded options (see MDTM
-- alternate flow 1 - a dropdown field with no active options blocks
-- validation until an admin adds some via the Field Options screen).
-- =====================================================================
INSERT INTO metadata_field_options (tenant_id, field_id, option_value, option_label, display_order, status)
SELECT mf.tenant_id, mf.field_id, v.option_value, v.option_label, v.display_order, 'active'
FROM metadata_fields mf
JOIN (VALUES
    ('classification_level', 'public',       'Public',        1),
    ('classification_level', 'internal',     'Internal',      2),
    ('classification_level', 'confidential', 'Confidential',  3),
    ('classification_level', 'restricted',   'Restricted',    4),
    ('currency',              'USD',           'US Dollar',            1),
    ('currency',              'EUR',           'Euro',                 2),
    ('currency',              'GBP',           'British Pound',        3),
    ('currency',              'LKR',           'Sri Lankan Rupee',     4),
    ('retention_class',        'short_term',     'Short-Term (1-3 years)',   1),
    ('retention_class',        'medium_term',    'Medium-Term (4-7 years)',  2),
    ('retention_class',        'long_term',      'Long-Term (8-15 years)',   3),
    ('retention_class',        'permanent',      'Permanent',                4)
) AS v(field_key, option_value, option_label, display_order)
    ON v.field_key = mf.field_key
WHERE NOT EXISTS (
    SELECT 1 FROM metadata_field_options o
    WHERE o.field_id = mf.field_id AND o.option_value = v.option_value
);

-- =====================================================================
-- document_types (4 starter types)
-- =====================================================================
INSERT INTO document_types (tenant_id, type_code, type_name, description, icon, display_order, requires_watermark, coediting_enabled, status)
SELECT t.tenant_id, v.type_code, v.type_name, v.description, v.icon, v.display_order, v.requires_watermark, v.coediting_enabled, 'active'
FROM tenants t
CROSS JOIN (VALUES
    ('CONTRACT', 'Contract', 'Legally binding agreements with a counterparty.', 'file-signature', 1, true,  false),
    ('INVOICE',  'Invoice',  'Vendor or customer billing documents.',           'receipt',         2, false, false),
    ('POLICY',   'Policy',   'Internal governance and policy documents.',       'shield-check',    3, true,  false),
    ('GENERAL',  'General',  'Catch-all type for documents with no closer fit.', 'file',           4, false, true)
) AS v(type_code, type_name, description, icon, display_order, requires_watermark, coediting_enabled)
ON CONFLICT (tenant_id, type_code) WHERE is_deleted = false DO NOTHING;

-- =====================================================================
-- document_type_fields - schema_version 1 mappings for the 4 starter types.
-- =====================================================================
INSERT INTO document_type_fields (tenant_id, document_type_id, field_id, schema_version, status, is_mandatory, display_order, field_group)
SELECT dt.tenant_id, dt.document_type_id, mf.field_id, 1, 'active', v.is_mandatory, v.display_order, v.field_group
FROM document_types dt
JOIN metadata_fields mf ON mf.tenant_id = dt.tenant_id
JOIN (VALUES
    -- Contract
    ('CONTRACT', 'contract_party',      true,  10,  'Contract Details'),
    ('CONTRACT', 'contract_value',      true,  20,  'Contract Details'),
    ('CONTRACT', 'contract_start_date', true,  30,  'Contract Details'),
    ('CONTRACT', 'contract_end_date',   true,  40,  'Contract Details'),
    ('CONTRACT', 'effective_date',      false, 50,  'Dates'),
    ('CONTRACT', 'expiry_date',         false, 60,  'Dates'),
    ('CONTRACT', 'review_due_date',     false, 70,  'Dates'),
    ('CONTRACT', 'classification_level',true,  80,  'Governance'),
    ('CONTRACT', 'retention_class',     true,  90,  'Governance'),
    ('CONTRACT', 'department',          false, 100, 'Governance'),
    ('CONTRACT', 'tags',                false, 110, 'General'),
    ('CONTRACT', 'description',         false, 120, 'General'),
    ('CONTRACT', 'reference_number',    false, 130, 'General'),
    -- Invoice
    ('INVOICE',  'invoice_number',        true,  10,  'Invoice Details'),
    ('INVOICE',  'invoice_date',          true,  20,  'Invoice Details'),
    ('INVOICE',  'amount',                true,  30,  'Invoice Details'),
    ('INVOICE',  'currency',              true,  40,  'Invoice Details'),
    ('INVOICE',  'vendor_supplier_name',  true,  50,  'Invoice Details'),
    ('INVOICE',  'purchase_order_number', false, 60,  'Invoice Details'),
    ('INVOICE',  'classification_level',  true,  70,  'Governance'),
    ('INVOICE',  'department',            false, 80,  'Governance'),
    ('INVOICE',  'cost_centre',           false, 90,  'Governance'),
    ('INVOICE',  'tags',                  false, 100, 'General'),
    ('INVOICE',  'description',           false, 110, 'General'),
    -- Policy
    ('POLICY',   'classification_level', true,  10,  'Governance'),
    ('POLICY',   'department',           true,  20,  'Governance'),
    ('POLICY',   'retention_class',      true,  30,  'Governance'),
    ('POLICY',   'effective_date',       true,  40,  'Dates'),
    ('POLICY',   'expiry_date',          false, 50,  'Dates'),
    ('POLICY',   'review_due_date',      true,  60,  'Dates'),
    ('POLICY',   'description',          false, 70,  'General'),
    ('POLICY',   'author',               false, 80,  'General'),
    ('POLICY',   'keywords',             false, 90,  'General'),
    ('POLICY',   'language',             false, 100, 'General'),
    ('POLICY',   'tags',                 false, 110, 'General'),
    ('POLICY',   'reference_number',     false, 120, 'General'),
    -- General
    ('GENERAL',  'classification_level', true,  10,  'Governance'),
    ('GENERAL',  'department',           false, 20,  'Governance'),
    ('GENERAL',  'retention_class',      false, 30,  'Governance'),
    ('GENERAL',  'description',          false, 40,  'General'),
    ('GENERAL',  'tags',                 false, 50,  'General'),
    ('GENERAL',  'keywords',             false, 60,  'General'),
    ('GENERAL',  'author',               false, 70,  'General'),
    ('GENERAL',  'language',             false, 80,  'General'),
    ('GENERAL',  'reference_number',     false, 90,  'General'),
    ('GENERAL',  'effective_date',       false, 100, 'Dates'),
    ('GENERAL',  'expiry_date',          false, 110, 'Dates')
) AS v(type_code, field_key, is_mandatory, display_order, field_group)
    ON v.type_code = dt.type_code AND v.field_key = mf.field_key
ON CONFLICT (document_type_id, field_id, schema_version) DO NOTHING;
