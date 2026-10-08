-- Module 2.7 storage refactor.
--
-- document_type_fields copied every mapping row on each schema publish and
-- repeated version-level data (schema_version/status/archived_at) on every
-- row. It is replaced by document_type_schemas: one row per
-- (document type, schema version), with the ordered field mapping held as
-- a JSONB array. Each element keeps the legacy row's id as "mappingId" so
-- existing documentTypeFieldId values survive unchanged:
--   {mappingId, fieldId, mandatory, hidden, requiresBackfill, displayOrder,
--    fieldGroup, defaultValueOverride}
--
-- document_metadata_values (one row per document x field x value) is
-- replaced by document_metadata: one row per document, values held as
--   {"<fieldId>": {"v": ..., "src": "manual|ocr|import|migration|integration", "conf": 0.97}}
--
-- document_type_fields itself is NOT dropped here: pods still on the
-- previous release validate their DocumentTypeField entity against it at
-- startup (ddl-auto: validate) during a rolling deploy. It is made
-- read-only instead, and dropped by V37 one release later.

-- =====================================================================
-- 1. document_type_schemas
-- =====================================================================
CREATE TABLE document_type_schemas (
    schema_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenants (tenant_id),
    document_type_id UUID NOT NULL,
    schema_version   INT  NOT NULL,
    status           VARCHAR(30) NOT NULL DEFAULT 'active',
    fields           JSONB NOT NULL DEFAULT '[]',
    published_by     UUID REFERENCES users (user_id),
    published_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at      TIMESTAMPTZ,
    CONSTRAINT ck_dts_status CHECK (status IN ('active', 'archived')),
    CONSTRAINT uq_dts_type_version UNIQUE (document_type_id, schema_version),
    -- Support constraint for document_metadata's tenant-safe composite FK.
    CONSTRAINT uq_dts_tenant_type_version UNIQUE (tenant_id, document_type_id, schema_version),
    CONSTRAINT fk_dts_tenant_type FOREIGN KEY (tenant_id, document_type_id)
        REFERENCES document_types (tenant_id, document_type_id)
);

-- Serves the "is this field mapped anywhere" containment query
-- (fields @> '[{"fieldId": "..."}]') behind the metadata-field delete guard.
CREATE INDEX ix_dts_fields ON document_type_schemas USING gin (fields jsonb_path_ops);

-- =====================================================================
-- 2. Backfill every live legacy version, preserving mapping ids.
-- =====================================================================
INSERT INTO document_type_schemas
    (tenant_id, document_type_id, schema_version, status, fields, published_by, published_at, archived_at)
SELECT dtf.tenant_id,
       dtf.document_type_id,
       dtf.schema_version,
       CASE WHEN bool_or(dtf.status = 'active') THEN 'active' ELSE 'archived' END,
       jsonb_agg(jsonb_build_object(
               'mappingId',            dtf.document_type_field_id,
               'fieldId',              dtf.field_id,
               'mandatory',            dtf.is_mandatory,
               'hidden',               dtf.is_hidden,
               'requiresBackfill',     dtf.requires_backfill,
               'displayOrder',         dtf.display_order,
               'fieldGroup',           dtf.field_group,
               'defaultValueOverride', dtf.default_value_override)
           ORDER BY dtf.display_order, dtf.document_type_field_id),
       (array_agg(dtf.created_by ORDER BY dtf.created_at, dtf.document_type_field_id))[1],
       min(dtf.created_at),
       max(dtf.archived_at)
FROM document_type_fields dtf
WHERE dtf.is_deleted = false
GROUP BY dtf.tenant_id, dtf.document_type_id, dtf.schema_version;

-- =====================================================================
-- 3. Every document type gets a row at its current version, empty if it
--    was never published, so document_metadata can always reference it.
-- =====================================================================
INSERT INTO document_type_schemas
    (tenant_id, document_type_id, schema_version, status, fields, published_by, published_at)
SELECT dt.tenant_id, dt.document_type_id, dt.current_schema_version, 'active', '[]'::jsonb,
       dt.created_by, dt.created_at
FROM document_types dt
WHERE NOT EXISTS (SELECT 1
                  FROM document_type_schemas s
                  WHERE s.document_type_id = dt.document_type_id
                    AND s.schema_version = dt.current_schema_version);

-- =====================================================================
-- 4. Freeze the legacy table until V37 drops it.
-- =====================================================================
CREATE FUNCTION document_type_fields_read_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'document_type_fields is read-only since V36; write to document_type_schemas instead'
        USING ERRCODE = 'read_only_sql_transaction';
END;
$$;

CREATE TRIGGER trg_document_type_fields_read_only
    BEFORE INSERT OR UPDATE OR DELETE ON document_type_fields
    FOR EACH STATEMENT EXECUTE FUNCTION document_type_fields_read_only();

-- =====================================================================
-- 5. Drop the unused per-value table - refusing if anything wrote to it.
-- =====================================================================
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM document_metadata_values) THEN
        RAISE EXCEPTION 'V36: document_metadata_values is not empty; migrate its rows into document_metadata before dropping it';
    END IF;
END;
$$;

DROP TABLE document_metadata_values;

-- =====================================================================
-- 6. document_metadata
-- =====================================================================
-- Support constraint for document_metadata's tenant-safe composite FK.
ALTER TABLE documents
    ADD CONSTRAINT uq_documents_tenant_document UNIQUE (tenant_id, document_id);

CREATE TABLE document_metadata (
    tenant_id        UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id      UUID NOT NULL,
    document_type_id UUID NOT NULL,
    schema_version   INT  NOT NULL,
    field_values     JSONB NOT NULL DEFAULT '{}',
    backfill_status  VARCHAR(30) NOT NULL DEFAULT 'none',
    created_by       UUID REFERENCES users (user_id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       UUID REFERENCES users (user_id),
    updated_at       TIMESTAMPTZ,
    CONSTRAINT pk_document_metadata PRIMARY KEY (tenant_id, document_id),
    CONSTRAINT ck_dm_backfill_status CHECK (backfill_status IN ('none', 'pending', 'completed')),
    -- Deferrable like V32's documents <-> document_versions FKs, so an
    -- upload can insert the document and its metadata in either order
    -- within one transaction.
    CONSTRAINT fk_dm_document FOREIGN KEY (tenant_id, document_id)
        REFERENCES documents (tenant_id, document_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_dm_schema FOREIGN KEY (tenant_id, document_type_id, schema_version)
        REFERENCES document_type_schemas (tenant_id, document_type_id, schema_version)
);

-- Default jsonb_ops, not jsonb_path_ops: the ? key-exists operator (is any
-- document holding a value for field X) needs it.
CREATE INDEX ix_dm_values ON document_metadata USING gin (field_values);
-- Backs fk_dm_schema and "documents of type X at schema version N".
CREATE INDEX ix_dm_type_version ON document_metadata (document_type_id, schema_version);
