-- Drop metadata/document-type columns that carry no enforced behaviour.
-- document_types.icon is never rendered as an icon anywhere in the UI.
-- metadata_fields.is_indexed creates no index - the name is misleading and
-- nothing reads the flag. is_searchable is kept; it's a distinct concept.
-- document_type_fields.is_readonly is never enforced server-side - the admin
-- UI disables the input, but the API accepts the write regardless.
-- is_hidden is kept: it IS enforced in MetadataValidationServiceImpl, which
-- skips hidden fields during validation.
ALTER TABLE document_types DROP COLUMN icon;
ALTER TABLE metadata_fields DROP COLUMN is_indexed;
ALTER TABLE document_type_fields DROP COLUMN is_readonly;

-- Display-order conflict prevention.
--
-- PostgreSQL cannot combine a partial unique index (WHERE is_deleted = false)
-- with a DEFERRABLE constraint: a bare unique INDEX can't be deferred, and a
-- deferrable unique CONSTRAINT can't carry a WHERE predicate. To get both
-- partial semantics (soft-deleted rows never collide with live ones or each
-- other) and deferred checking (a reorder swap transiently duplicates a
-- value mid-transaction), each table gets a STORED generated column that
-- collapses soft-deleted rows to NULL - Postgres unique constraints treat
-- every NULL as distinct, so deleted rows never participate in the
-- uniqueness check - with a real deferrable UNIQUE constraint on top of it.

-- Pre-existing data can carry duplicate display_order values among live
-- rows (e.g. seeded before ordering was enforced), which would make the
-- unique constraints below fail to create. Collapse each duplicate group
-- to the app's own canonical gap-free (index+1)*10 sequence - the same
-- scheme DisplayOrderNormalizer re-stamps siblings to on every
-- create/update - before the constraint is added, so ties break on the
-- existing display_order first and fall back to the row's id.

-- document_type_fields (document_type_id, schema_version, display_order) WHERE is_deleted = false
WITH ranked AS (
    SELECT document_type_field_id,
           ROW_NUMBER() OVER (
               PARTITION BY document_type_id, schema_version
               ORDER BY display_order, document_type_field_id
           ) AS rn
    FROM document_type_fields
    WHERE is_deleted = false
)
UPDATE document_type_fields dtf
SET display_order = ranked.rn * 10
FROM ranked
WHERE dtf.document_type_field_id = ranked.document_type_field_id
  AND dtf.display_order <> ranked.rn * 10;

ALTER TABLE document_type_fields
    ADD COLUMN display_order_uniq_key int
        GENERATED ALWAYS AS (CASE WHEN is_deleted THEN NULL ELSE display_order END) STORED;
ALTER TABLE document_type_fields
    ADD CONSTRAINT uq_dtf_type_version_order
        UNIQUE (document_type_id, schema_version, display_order_uniq_key)
        DEFERRABLE INITIALLY DEFERRED;

-- metadata_field_options (field_id, display_order) WHERE is_deleted = false
WITH ranked AS (
    SELECT option_id,
           ROW_NUMBER() OVER (
               PARTITION BY field_id
               ORDER BY display_order, option_id
           ) AS rn
    FROM metadata_field_options
    WHERE is_deleted = false
)
UPDATE metadata_field_options mfo
SET display_order = ranked.rn * 10
FROM ranked
WHERE mfo.option_id = ranked.option_id
  AND mfo.display_order <> ranked.rn * 10;

ALTER TABLE metadata_field_options
    ADD COLUMN display_order_uniq_key int
        GENERATED ALWAYS AS (CASE WHEN is_deleted THEN NULL ELSE display_order END) STORED;
ALTER TABLE metadata_field_options
    ADD CONSTRAINT uq_mfo_field_order
        UNIQUE (field_id, display_order_uniq_key)
        DEFERRABLE INITIALLY DEFERRED;

-- document_types (tenant_id, display_order) WHERE is_deleted = false
WITH ranked AS (
    SELECT document_type_id,
           ROW_NUMBER() OVER (
               PARTITION BY tenant_id
               ORDER BY display_order, document_type_id
           ) AS rn
    FROM document_types
    WHERE is_deleted = false
)
UPDATE document_types dt
SET display_order = ranked.rn * 10
FROM ranked
WHERE dt.document_type_id = ranked.document_type_id
  AND dt.display_order <> ranked.rn * 10;

ALTER TABLE document_types
    ADD COLUMN display_order_uniq_key int
        GENERATED ALWAYS AS (CASE WHEN is_deleted THEN NULL ELSE display_order END) STORED;
ALTER TABLE document_types
    ADD CONSTRAINT uq_dt_tenant_order
        UNIQUE (tenant_id, display_order_uniq_key)
        DEFERRABLE INITIALLY DEFERRED;
