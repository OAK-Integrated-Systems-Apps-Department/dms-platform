-- 2.8 DRM - the 10 system relationship types for every existing tenant.
-- New tenants get the same rows from RelationshipTypeSeeder (TenantProvisioningService);
-- RelationshipTypeSeedParityTest keeps this list and RelationshipTypeCatalog identical.
-- Idempotent: a tenant that already has a type with the code is left alone.

INSERT INTO relationship_types (tenant_id, code, category, label, inverse_label, directionality,
                                prevents_cycles, is_system, is_enabled, display_order)
SELECT t.tenant_id, v.code, v.category, v.label, v.inverse_label, v.directionality,
       v.prevents_cycles, true, true, v.display_order
FROM tenants t
CROSS JOIN (VALUES
    ('PARENT_CHILD',         'structure',   'Parent of',               'Child of',           'directional', true,  10),
    ('MAIN_ATTACHMENT',      'structure',   'Has attachment',          'Attachment of',      'directional', false, 20),
    ('CONTRACT_AMENDMENT',   'contract',    'Has amendment',           'Amends',             'directional', false, 30),
    ('SUPERSEDES',           'versioning',  'Supersedes',              'Superseded by',      'directional', true,  40),
    ('REPLACES',             'versioning',  'Replaces',                'Replaced by',        'directional', true,  50),
    ('INVOICE_SUPPORTING',   'evidence',    'Has supporting document', 'Supports',           'directional', false, 60),
    ('REPORT_EVIDENCE',      'evidence',    'Has evidence',            'Evidence for',       'directional', false, 70),
    ('ORIGINAL_TRANSLATION', 'language',    'Has translation',         'Translation of',     'directional', false, 80),
    ('DUPLICATE_OF',         'duplicate',   'Duplicate of',            'Has duplicate',      'directional', false, 90),
    ('RELATED',              'association', 'Related document',        'Related document',   'symmetric',   false, 100)
) AS v (code, category, label, inverse_label, directionality, prevents_cycles, display_order)
ON CONFLICT (tenant_id, code) WHERE is_deleted = false DO NOTHING;
