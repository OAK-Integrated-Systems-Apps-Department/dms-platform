-- classification_level is dropped from com.oak.dms.metadata.seed.StarterCatalog (no new tenant gets it
-- from now on); this backfills the removal into tenants that already got it from V37, or from the
-- installer before this change, without touching a tenant that has since customized the field or its
-- mapping itself.
--
-- "Untouched starter version" of the field (the only thing this migration will act on) means: still
-- present, not soft-deleted, status = 'active', is_system_field = true, and its label/help_text/data_type
-- still read exactly as the starter catalogue defines them. Anything else - renamed, repurposed, turned
-- optional, deactivated, deprecated - is a tenant's own change and is left alone, mirroring V37's own
-- "skip what's already there" rule.
--
-- Mapping removal is a direct, in-place edit of the *current* active document_type_schemas.fields array
-- (no new schema_version is published): DocumentTypeFieldServiceImpl#getSchema only ever reads the row
-- whose schema_version equals document_types.current_schema_version and filters its fields against live
-- metadata_fields rows, so neither a version bump nor a predecessor "archived" row is needed for the
-- result to read as a coherent, current schema - and leaving schema_version alone keeps every
-- never-republished document type's version exactly where the starter catalogue installer left it. Only
-- a mapping entry that still matches the starter mapping exactly (mandatory = true) is removed; a type
-- whose admin has since made the field optional, or unmapped it already, is left untouched.
--
-- The field definition itself is then, per tenant:
--   * hard-deleted (with its options and any validations), the same as MetadataFieldServiceImpl#deleteField
--     does for a field that ends up mapped nowhere and carries no document_metadata values;
--   * deprecated (status = 'deprecated'), the same as deleteField's "mapped/has values -> deprecate
--     instead" rule, when documents still carry a saved value for it (orphaning that stored value, exactly
--     as deleting a field today leaves document_metadata rows for the deleted field_id untouched); or
--   * left alone entirely, if it is still mapped on some document type this migration did not touch
--     (because that type's mapping had already been customized away from the starter shape).
--
-- document_metadata.field_values for classification_level are never modified: the admin UI's own
-- field-deletion path leaves a deleted/deprecated field's stored values exactly where they are, and this
-- mirrors that.

CREATE TEMP TABLE v102_report (seq serial, step text NOT NULL, n bigint NOT NULL) ON COMMIT DROP;

-- =====================================================================
-- 1. The untouched starter-version classification_level field, per tenant.
-- =====================================================================
CREATE TEMP TABLE v102_fields (tenant_id uuid, field_id uuid) ON COMMIT DROP;
INSERT INTO v102_fields (tenant_id, field_id)
SELECT mf.tenant_id, mf.field_id
FROM metadata_fields mf
WHERE mf.field_key = 'classification_level'
  AND mf.is_deleted = false
  AND mf.status = 'active'
  AND mf.is_system_field = true
  AND mf.label = 'Classification Level'
  AND mf.help_text = 'Sensitivity classification of the document.'
  AND mf.data_type = 'dropdown';
INSERT INTO v102_report (step, n) SELECT 'candidate untouched classification_level fields', count(*) FROM v102_fields;

-- =====================================================================
-- 2. Strip the field out of every current active schema where it is still
--    mapped exactly as the starter catalogue mapped it (mandatory = true).
--    A type where it was already made optional, hidden behind a different
--    mapping, or unmapped is left untouched.
-- =====================================================================
WITH touched AS (
    SELECT s.schema_id, s.fields
    FROM document_type_schemas s
    JOIN document_types dt ON dt.document_type_id = s.document_type_id AND dt.tenant_id = s.tenant_id
    JOIN v102_fields vf ON vf.tenant_id = s.tenant_id
    WHERE s.status = 'active'
      AND s.schema_version = dt.current_schema_version
      AND EXISTS (SELECT 1 FROM jsonb_array_elements(s.fields) e
                  WHERE e ->> 'fieldId' = vf.field_id::text AND (e ->> 'mandatory')::boolean = true)
),
upd AS (
    UPDATE document_type_schemas s
    SET fields = (SELECT coalesce(jsonb_agg(e), '[]'::jsonb)
                  FROM jsonb_array_elements(t.fields) e
                  WHERE NOT (e ->> 'fieldId' IN (SELECT field_id::text FROM v102_fields WHERE tenant_id = s.tenant_id)
                             AND (e ->> 'mandatory')::boolean = true))
    FROM touched t
    WHERE s.schema_id = t.schema_id
    RETURNING s.schema_id)
INSERT INTO v102_report (step, n) SELECT 'unmapped classification_level from document_type_schemas', count(*) FROM upd;

-- =====================================================================
-- 3. Resolve each candidate field: hard-delete if now unused everywhere,
--    deprecate if documents still carry a saved value, else leave alone
--    (it is still mapped on some type this migration did not touch).
-- =====================================================================
CREATE TEMP TABLE v102_still_mapped (field_id uuid) ON COMMIT DROP;
INSERT INTO v102_still_mapped
SELECT DISTINCT vf.field_id
FROM v102_fields vf
JOIN document_type_schemas s ON s.tenant_id = vf.tenant_id AND s.status = 'active'
JOIN document_types dt ON dt.document_type_id = s.document_type_id AND dt.tenant_id = s.tenant_id
                       AND dt.current_schema_version = s.schema_version
WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(s.fields) e WHERE e ->> 'fieldId' = vf.field_id::text);

CREATE TEMP TABLE v102_has_values (field_id uuid) ON COMMIT DROP;
INSERT INTO v102_has_values
SELECT vf.field_id
FROM v102_fields vf
WHERE EXISTS (SELECT 1 FROM document_metadata dm
              WHERE dm.tenant_id = vf.tenant_id AND dm.field_values ? vf.field_id::text);

CREATE TEMP TABLE v102_to_delete (tenant_id uuid, field_id uuid) ON COMMIT DROP;
INSERT INTO v102_to_delete
SELECT tenant_id, field_id FROM v102_fields
WHERE field_id NOT IN (SELECT field_id FROM v102_still_mapped)
  AND field_id NOT IN (SELECT field_id FROM v102_has_values);

CREATE TEMP TABLE v102_to_deprecate (tenant_id uuid, field_id uuid) ON COMMIT DROP;
INSERT INTO v102_to_deprecate
SELECT tenant_id, field_id FROM v102_fields
WHERE field_id NOT IN (SELECT field_id FROM v102_still_mapped)
  AND field_id IN (SELECT field_id FROM v102_has_values);

WITH d AS (DELETE FROM metadata_field_validations v
           WHERE v.field_id IN (SELECT field_id FROM v102_to_delete) RETURNING 1)
INSERT INTO v102_report (step, n) SELECT 'deleted metadata_field_validations', count(*) FROM d;

WITH d AS (DELETE FROM metadata_field_options o
           WHERE o.field_id IN (SELECT field_id FROM v102_to_delete) RETURNING 1)
INSERT INTO v102_report (step, n) SELECT 'deleted metadata_field_options', count(*) FROM d;

WITH d AS (DELETE FROM metadata_fields mf
           WHERE mf.field_id IN (SELECT field_id FROM v102_to_delete) RETURNING 1)
INSERT INTO v102_report (step, n) SELECT 'deleted metadata_fields', count(*) FROM d;

WITH u AS (UPDATE metadata_fields mf
           SET status = 'deprecated', updated_at = now()
           WHERE mf.field_id IN (SELECT field_id FROM v102_to_deprecate) RETURNING 1)
INSERT INTO v102_report (step, n) SELECT 'deprecated metadata_fields (still has document values)', count(*) FROM u;

INSERT INTO v102_report (step, n) SELECT 'left alone (still mapped on an untouched type)',
       count(*) FROM v102_still_mapped;

-- =====================================================================
-- 4. Report
-- =====================================================================
DO
$$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT step, n FROM v102_report ORDER BY seq LOOP
        RAISE NOTICE 'V102: % = %', r.step, r.n;
    END LOOP;
END;
$$;
