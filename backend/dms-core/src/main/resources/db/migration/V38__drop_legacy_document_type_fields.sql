-- document_type_fields was replaced by document_type_schemas in V36 and has
-- been read-only since. Nothing reads or writes it any more.
--
-- Completes the drop that V36's header deferred ("dropped by V37 one release
-- later"); V37 reassigned it here.

-- =====================================================================
-- 1. Guard: refuse if any live legacy row never made it into
--    document_type_schemas (V36 backfilled is_deleted = false rows only).
-- =====================================================================
DO
$$
DECLARE
    n bigint;
BEGIN
    SELECT count(*) INTO n
    FROM document_type_fields dtf
    WHERE dtf.is_deleted = false
      AND NOT EXISTS (SELECT 1
                      FROM document_type_schemas s
                      WHERE s.document_type_id = dtf.document_type_id
                        AND s.schema_version = dtf.schema_version
                        AND s.fields @> jsonb_build_array(
                                jsonb_build_object('mappingId', dtf.document_type_field_id)));
    IF n > 0 THEN
        RAISE EXCEPTION 'V38: % live document_type_fields row(s) have no matching mappingId in document_type_schemas; backfill them before dropping the table', n;
    END IF;
END;
$$;

-- =====================================================================
-- 2. Drop the legacy table and its read-only trigger.
-- =====================================================================
DROP TRIGGER IF EXISTS trg_document_type_fields_read_only ON document_type_fields;
DROP FUNCTION IF EXISTS document_type_fields_read_only();
DROP TABLE IF EXISTS document_type_fields;
