-- Removes the V24 platform tenant / system user and the V30 starter catalogue
-- that landed in it, then gives every real tenant its own copy of that
-- catalogue.
--
-- V24 seeded tenant 00000000-0000-0000-0000-000000000000 and user
-- 00000000-0000-0000-0000-000000000001 so audit_events.actor_id could name a
-- real row for system-triggered events. Nothing needs the rows: actor_id has
-- no FK (V23), SystemActor.SYSTEM_USER_ID stays as a reserved constant, and
-- platform-level audit events use tenant_id NULL. V30 seeded "FROM tenants t",
-- so on a fresh database its whole catalogue went to that fake tenant, where
-- nobody can log in, and every real tenant started empty.
--
-- Aborts, changing nothing, if the fake tenant or user owns anything beyond
-- the V30 catalogue. audit_events rows are never modified.
--
-- The catalogue in section 5 mirrors com.oak.dms.metadata.seed.StarterCatalog,
-- which installs it for tenants created from now on; V37MigrationIT asserts
-- the two stay identical.
--
-- The document_type_fields drop that V36's header assigns to "V37" is V38.

CREATE TEMP TABLE v37_report (seq serial, step text NOT NULL, n bigint NOT NULL) ON COMMIT DROP;

-- =====================================================================
-- 1. Guards
-- =====================================================================
DO
$$
DECLARE
    fake_tenant CONSTANT uuid   := '00000000-0000-0000-0000-000000000000';
    fake_user   CONSTANT uuid   := '00000000-0000-0000-0000-000000000001';
    catalogue   CONSTANT text[] := ARRAY['metadata_fields', 'metadata_field_options', 'metadata_field_validations',
                                         'document_types', 'document_type_fields', 'document_type_schemas'];
    r record;
    n bigint;
BEGIN
    SELECT count(*) INTO n FROM audit_events WHERE tenant_id = fake_tenant;
    IF n > 0 THEN
        RAISE EXCEPTION 'V37: % audit_events row(s) belong to platform tenant %; audit rows are never modified - resolve manually before migrating', n, fake_tenant;
    END IF;

    SELECT count(*) INTO n FROM documents d
    WHERE d.tenant_id = fake_tenant
       OR d.document_type_id IN (SELECT document_type_id FROM document_types WHERE tenant_id = fake_tenant);
    IF n > 0 THEN
        RAISE EXCEPTION 'V37: % documents row(s) belong to platform tenant % or use its document types', n, fake_tenant;
    END IF;

    SELECT count(*) INTO n FROM document_metadata dm
    WHERE dm.tenant_id = fake_tenant
       OR dm.document_type_id IN (SELECT document_type_id FROM document_types WHERE tenant_id = fake_tenant)
       OR EXISTS (SELECT 1 FROM jsonb_object_keys(dm.field_values) k
                  WHERE k IN (SELECT field_id::text FROM metadata_fields WHERE tenant_id = fake_tenant));
    IF n > 0 THEN
        RAISE EXCEPTION 'V37: % document_metadata row(s) belong to platform tenant % or reference its types/fields', n, fake_tenant;
    END IF;

    SELECT count(*) INTO n FROM documents WHERE owner_user_id = fake_user;
    IF n > 0 THEN
        RAISE EXCEPTION 'V37: % documents row(s) are owned by system user %', n, fake_user;
    END IF;

    -- Another tenant's rows pointing into the platform catalogue (these
    -- references are not tenant-composite FKs, so nothing else stops them).
    SELECT count(*) INTO n
    FROM document_type_schemas s, jsonb_array_elements(s.fields) e
    WHERE s.tenant_id <> fake_tenant
      AND e ->> 'fieldId' IN (SELECT field_id::text FROM metadata_fields WHERE tenant_id = fake_tenant);
    SELECT n + count(*) INTO n FROM metadata_field_options o
    WHERE o.tenant_id <> fake_tenant
      AND (o.field_id IN (SELECT field_id FROM metadata_fields WHERE tenant_id = fake_tenant)
           OR o.parent_option_id IN (SELECT option_id FROM metadata_field_options WHERE tenant_id = fake_tenant));
    SELECT n + count(*) INTO n FROM metadata_field_validations v
    WHERE v.tenant_id <> fake_tenant
      AND v.field_id IN (SELECT field_id FROM metadata_fields WHERE tenant_id = fake_tenant);
    IF n > 0 THEN
        RAISE EXCEPTION 'V37: % row(s) in other tenants reference platform tenant % catalogue rows', n, fake_tenant;
    END IF;

    -- Every other table with an FK to tenants must hold nothing for the fake
    -- tenant, apart from the system user itself and its own role grants
    -- (deleted in section 3).
    FOR r IN
        SELECT c.conrelid::regclass AS tbl, a.attname AS col
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        WHERE c.contype = 'f' AND c.confrelid = 'tenants'::regclass AND cardinality(c.conkey) = 1
          AND c.conrelid::regclass::text <> ALL (catalogue)
        ORDER BY 1::text, 2
    LOOP
        IF r.tbl = 'users'::regclass THEN
            SELECT count(*) INTO n FROM users WHERE tenant_id = fake_tenant AND user_id <> fake_user;
        ELSIF r.tbl = 'user_roles'::regclass THEN
            SELECT count(*) INTO n FROM user_roles WHERE tenant_id = fake_tenant AND user_id <> fake_user;
        ELSE
            EXECUTE format('SELECT count(*) FROM %s WHERE %I = $1', r.tbl, r.col) INTO n USING fake_tenant;
        END IF;
        IF n > 0 THEN
            RAISE EXCEPTION 'V37: % row(s) in %.% belong to platform tenant %', n, r.tbl, r.col, fake_tenant;
        END IF;
    END LOOP;

    -- NOT NULL FKs to users: only user_roles / password_reset_tokens may
    -- reference the system user (deleted in section 3); anything else aborts.
    FOR r IN
        SELECT c.conrelid::regclass AS tbl, a.attname AS col
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        WHERE c.contype = 'f' AND c.confrelid = 'users'::regclass AND cardinality(c.conkey) = 1
          AND a.attnotnull
          AND (c.conrelid::regclass::text, a.attname::text) NOT IN (('user_roles', 'user_id'),
                                                                   ('password_reset_tokens', 'user_id'))
        ORDER BY 1::text, 2
    LOOP
        EXECUTE format('SELECT count(*) FROM %s WHERE %I = $1', r.tbl, r.col) INTO n USING fake_user;
        IF n > 0 THEN
            RAISE EXCEPTION 'V37: % row(s) in %.% reference system user % and cannot be nulled', n, r.tbl, r.col, fake_user;
        END IF;
    END LOOP;
END;
$$;

-- =====================================================================
-- 2. Delete the platform tenant's catalogue. One statement: FK checks run
--    at statement end, so sub-statement order doesn't matter.
-- =====================================================================
ALTER TABLE document_type_fields DISABLE TRIGGER trg_document_type_fields_read_only;

WITH dm  AS (DELETE FROM document_metadata          WHERE tenant_id = '00000000-0000-0000-0000-000000000000' RETURNING 1),
     dts AS (DELETE FROM document_type_schemas      WHERE tenant_id = '00000000-0000-0000-0000-000000000000' RETURNING 1),
     dtf AS (DELETE FROM document_type_fields       WHERE tenant_id = '00000000-0000-0000-0000-000000000000' RETURNING 1),
     mfo AS (DELETE FROM metadata_field_options     WHERE tenant_id = '00000000-0000-0000-0000-000000000000' RETURNING 1),
     mfv AS (DELETE FROM metadata_field_validations WHERE tenant_id = '00000000-0000-0000-0000-000000000000' RETURNING 1),
     dt  AS (DELETE FROM document_types             WHERE tenant_id = '00000000-0000-0000-0000-000000000000' RETURNING 1),
     mf  AS (DELETE FROM metadata_fields            WHERE tenant_id = '00000000-0000-0000-0000-000000000000' RETURNING 1)
INSERT INTO v37_report (step, n)
VALUES ('deleted document_metadata',          (SELECT count(*) FROM dm)),
       ('deleted document_type_schemas',      (SELECT count(*) FROM dts)),
       ('deleted document_type_fields',       (SELECT count(*) FROM dtf)),
       ('deleted metadata_field_options',     (SELECT count(*) FROM mfo)),
       ('deleted metadata_field_validations', (SELECT count(*) FROM mfv)),
       ('deleted document_types',             (SELECT count(*) FROM dt)),
       ('deleted metadata_fields',            (SELECT count(*) FROM mf));

ALTER TABLE document_type_fields ENABLE TRIGGER trg_document_type_fields_read_only;

-- =====================================================================
-- 3. Clear every reference to the system user.
-- =====================================================================
DO
$$
DECLARE
    fake_user CONSTANT uuid := '00000000-0000-0000-0000-000000000001';
    r     record;
    found boolean;
    n     bigint;
BEGIN
    FOR r IN
        SELECT c.conrelid::regclass AS tbl, a.attname AS col
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        WHERE c.contype = 'f' AND c.confrelid = 'users'::regclass AND cardinality(c.conkey) = 1
          AND NOT a.attnotnull
        ORDER BY 1::text, 2
    LOOP
        -- Probe first: document_type_fields' statement trigger fires even on
        -- a zero-row UPDATE.
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s WHERE %I = $1)', r.tbl, r.col) INTO found USING fake_user;
        CONTINUE WHEN NOT found;
        IF r.tbl = 'document_type_fields'::regclass THEN
            ALTER TABLE document_type_fields DISABLE TRIGGER trg_document_type_fields_read_only;
        END IF;
        EXECUTE format('UPDATE %s SET %I = NULL WHERE %I = $1', r.tbl, r.col, r.col) USING fake_user;
        GET DIAGNOSTICS n = ROW_COUNT;
        IF r.tbl = 'document_type_fields'::regclass THEN
            ALTER TABLE document_type_fields ENABLE TRIGGER trg_document_type_fields_read_only;
        END IF;
        INSERT INTO v37_report (step, n) VALUES (format('nulled %s.%s', r.tbl, r.col), n);
    END LOOP;

    DELETE FROM password_reset_tokens WHERE user_id = fake_user;
    GET DIAGNOSTICS n = ROW_COUNT;
    INSERT INTO v37_report (step, n) VALUES ('deleted password_reset_tokens', n);

    DELETE FROM user_roles WHERE user_id = fake_user;
    GET DIAGNOSTICS n = ROW_COUNT;
    INSERT INTO v37_report (step, n) VALUES ('deleted user_roles', n);
END;
$$;

-- =====================================================================
-- 4. The system user, then the platform tenant.
-- =====================================================================
WITH d AS (DELETE FROM users WHERE user_id = '00000000-0000-0000-0000-000000000001' RETURNING 1)
INSERT INTO v37_report (step, n) SELECT 'deleted users', count(*) FROM d;

WITH d AS (DELETE FROM tenants WHERE tenant_id = '00000000-0000-0000-0000-000000000000' RETURNING 1)
INSERT INTO v37_report (step, n) SELECT 'deleted tenants', count(*) FROM d;

-- =====================================================================
-- 5. Starter catalogue for every live tenant. Values are V30's, with the
--    display orders V31 re-stamped them to.
-- =====================================================================
CREATE TEMP TABLE v37_fields (ord int, field_key text, label text, help_text text, data_type text,
                              is_system_field boolean) ON COMMIT DROP;
INSERT INTO v37_fields VALUES
    ( 1, 'classification_level',  'Classification Level',   'Sensitivity classification of the document.',   'dropdown',     true),
    ( 2, 'department',            'Department',             'Owning department or business unit.',           'lookup',       false),
    ( 3, 'tags',                  'Tags',                   'Free-form labels for grouping and discovery.',  'multi_select', false),
    ( 4, 'retention_class',       'Retention Class',        'Retention schedule this document falls under.', 'dropdown',     true),
    ( 5, 'description',           'Description',            'Free-text summary of the document.',            'long_text',    false),
    ( 6, 'author',                'Author',                 'Person or team who authored the document.',     'text',         false),
    ( 7, 'keywords',              'Keywords',               'Search keywords beyond the free-text tags.',    'multi_select', false),
    ( 8, 'language',              'Language',               'Primary language of the document content.',     'dropdown',     false),
    ( 9, 'reference_number',      'Reference Number',       'External or internal reference identifier.',    'text',         false),
    (10, 'effective_date',        'Effective Date',         'Date the document takes effect.',               'date',         false),
    (11, 'expiry_date',           'Expiry Date',            'Date the document expires.',                    'date',         false),
    (12, 'review_due_date',       'Review Due Date',        'Date the document is next due for review.',     'date',         false),
    (13, 'invoice_number',        'Invoice Number',         'Vendor-issued invoice number.',                 'text',         false),
    (14, 'invoice_date',          'Invoice Date',           'Date the invoice was issued.',                  'date',         false),
    (15, 'amount',                'Amount',                 'Monetary amount on the document.',              'decimal',      false),
    (16, 'currency',              'Currency',               'Currency the amount is denominated in.',        'dropdown',     false),
    (17, 'vendor_supplier_name',  'Vendor / Supplier Name', 'Name of the vendor or supplier.',               'text',         false),
    (18, 'purchase_order_number', 'Purchase Order Number',  'Related purchase order number.',                'text',         false),
    (19, 'contract_party',        'Contract Party',         'Counterparty named on the contract.',           'text',         false),
    (20, 'contract_value',        'Contract Value',         'Total monetary value of the contract.',         'decimal',      false),
    (21, 'contract_start_date',   'Contract Start Date',    'Date the contract starts.',                     'date',         false),
    (22, 'contract_end_date',     'Contract End Date',      'Date the contract ends.',                       'date',         false),
    (23, 'employee_id',           'Employee ID',            'Related employee identifier.',                  'text',         false),
    (24, 'project_code',          'Project Code',           'Project this document belongs to.',             'dropdown',     false),
    (25, 'cost_centre',           'Cost Centre',            'Cost centre to charge.',                        'dropdown',     false);

CREATE TEMP TABLE v37_options (field_key text, option_value text, option_label text, display_order int) ON COMMIT DROP;
INSERT INTO v37_options VALUES
    ('classification_level', 'public',       'Public',                  10),
    ('classification_level', 'internal',     'Internal',                20),
    ('classification_level', 'confidential', 'Confidential',            30),
    ('classification_level', 'restricted',   'Restricted',              40),
    ('currency',             'USD',          'US Dollar',               10),
    ('currency',             'EUR',          'Euro',                    20),
    ('currency',             'GBP',          'British Pound',           30),
    ('currency',             'LKR',          'Sri Lankan Rupee',        40),
    ('retention_class',      'short_term',   'Short-Term (1-3 years)',  10),
    ('retention_class',      'medium_term',  'Medium-Term (4-7 years)', 20),
    ('retention_class',      'long_term',    'Long-Term (8-15 years)',  30),
    ('retention_class',      'permanent',    'Permanent',               40);

CREATE TEMP TABLE v37_types (ord int, type_code text, type_name text, description text,
                             requires_watermark boolean, coediting_enabled boolean) ON COMMIT DROP;
INSERT INTO v37_types VALUES
    (1, 'CONTRACT', 'Contract', 'Legally binding agreements with a counterparty.',  true,  false),
    (2, 'INVOICE',  'Invoice',  'Vendor or customer billing documents.',            false, false),
    (3, 'POLICY',   'Policy',   'Internal governance and policy documents.',        true,  false),
    (4, 'GENERAL',  'General',  'Catch-all type for documents with no closer fit.', false, true);

CREATE TEMP TABLE v37_mappings (type_code text, field_key text, is_mandatory boolean, display_order int,
                                field_group text) ON COMMIT DROP;
INSERT INTO v37_mappings VALUES
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
    ('GENERAL',  'expiry_date',          false, 110, 'Dates');

CREATE TEMP TABLE v37_new_fields (tenant_id uuid, field_id uuid, field_key text) ON COMMIT DROP;
CREATE TEMP TABLE v37_new_types  (tenant_id uuid, document_type_id uuid, type_code text) ON COMMIT DROP;

-- Fields: skip keys the tenant already has live, whatever their status. A
-- soft-deleted field doesn't count, so a fresh one is created.
WITH ins AS (
    INSERT INTO metadata_fields (tenant_id, field_key, label, help_text, data_type, is_system_field, is_searchable, status)
    SELECT t.tenant_id, f.field_key, f.label, f.help_text, f.data_type, f.is_system_field, true, 'active'
    FROM tenants t CROSS JOIN v37_fields f
    WHERE t.is_deleted = false
    ORDER BY t.tenant_id, f.ord
    ON CONFLICT (tenant_id, field_key) WHERE is_deleted = false DO NOTHING
    RETURNING tenant_id, field_id, field_key)
INSERT INTO v37_new_fields SELECT tenant_id, field_id, field_key FROM ins;
INSERT INTO v37_report (step, n) SELECT 'backfilled metadata_fields', count(*) FROM v37_new_fields;

-- Options only on fields this migration created, never on a tenant's own.
WITH ins AS (
    INSERT INTO metadata_field_options (tenant_id, field_id, option_value, option_label, display_order, status)
    SELECT nf.tenant_id, nf.field_id, o.option_value, o.option_label, o.display_order, 'active'
    FROM v37_new_fields nf JOIN v37_options o ON o.field_key = nf.field_key
    RETURNING 1)
INSERT INTO v37_report (step, n) SELECT 'backfilled metadata_field_options', count(*) FROM ins;

-- Types: missing codes only, ordered after the tenant's live maximum in
-- canonical 10-steps, so uq_dt_tenant_order can't collide.
WITH missing AS (
    SELECT t.tenant_id, ty.*, row_number() OVER (PARTITION BY t.tenant_id ORDER BY ty.ord) AS rn
    FROM tenants t CROSS JOIN v37_types ty
    WHERE t.is_deleted = false
      AND NOT EXISTS (SELECT 1 FROM document_types dt
                      WHERE dt.tenant_id = t.tenant_id AND dt.type_code = ty.type_code AND dt.is_deleted = false)),
base AS (
    SELECT t.tenant_id, coalesce(max(dt.display_order), 0) AS max_order
    FROM tenants t
    LEFT JOIN document_types dt ON dt.tenant_id = t.tenant_id AND dt.is_deleted = false
    WHERE t.is_deleted = false
    GROUP BY t.tenant_id),
ins AS (
    INSERT INTO document_types (tenant_id, type_code, type_name, description, display_order, current_schema_version,
                                requires_watermark, coediting_enabled, status)
    SELECT m.tenant_id, m.type_code, m.type_name, m.description, b.max_order + m.rn * 10, 1,
           m.requires_watermark, m.coediting_enabled, 'active'
    FROM missing m JOIN base b USING (tenant_id)
    ON CONFLICT (tenant_id, type_code) WHERE is_deleted = false DO NOTHING
    RETURNING tenant_id, document_type_id, type_code)
INSERT INTO v37_new_types SELECT tenant_id, document_type_id, type_code FROM ins;
INSERT INTO v37_report (step, n) SELECT 'backfilled document_types', count(*) FROM v37_new_types;
INSERT INTO v37_report (step, n)
SELECT 'backfilled tenants', count(DISTINCT tenant_id)
FROM (SELECT tenant_id FROM v37_new_fields UNION SELECT tenant_id FROM v37_new_types) touched;

-- A v1 schema per new type, in V36's element shape. Maps only live, active
-- fields, whether created above or already the tenant's own.
WITH ins AS (
    INSERT INTO document_type_schemas (tenant_id, document_type_id, schema_version, status, fields, published_by, published_at)
    SELECT nt.tenant_id, nt.document_type_id, dt.current_schema_version, 'active',
           coalesce((SELECT jsonb_agg(jsonb_build_object(
                                'mappingId',            gen_random_uuid(),
                                'fieldId',              mf.field_id,
                                'mandatory',            m.is_mandatory,
                                'hidden',               false,
                                'requiresBackfill',     false,
                                'displayOrder',         m.display_order,
                                'fieldGroup',           m.field_group,
                                'defaultValueOverride', NULL)
                            ORDER BY m.display_order)
                     FROM v37_mappings m
                     JOIN metadata_fields mf ON mf.tenant_id = nt.tenant_id AND mf.field_key = m.field_key
                                            AND mf.is_deleted = false AND mf.status = 'active'
                     WHERE m.type_code = nt.type_code), '[]'::jsonb),
           NULL, now()
    FROM v37_new_types nt
    JOIN document_types dt ON dt.document_type_id = nt.document_type_id
    WHERE NOT EXISTS (SELECT 1 FROM document_type_schemas s
                      WHERE s.document_type_id = nt.document_type_id AND s.schema_version = dt.current_schema_version)
    RETURNING 1)
INSERT INTO v37_report (step, n) SELECT 'backfilled document_type_schemas', count(*) FROM ins;

-- =====================================================================
-- 6. Report
-- =====================================================================
DO
$$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT step, n FROM v37_report ORDER BY seq LOOP
        RAISE NOTICE 'V37: % = %', r.step, r.n;
    END LOOP;
END;
$$;
