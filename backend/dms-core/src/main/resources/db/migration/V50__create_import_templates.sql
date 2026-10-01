-- Module 2.1 - File Import/Export: saved import templates (FIE "Manage
-- Import Templates").
--
-- A tenant-wide, named template for one document type: which of its fields
-- are columns (in what order, under which header) and default values for
-- fields left out or left blank. Downloading it gives the usual .xlsx/.csv
-- template, built from the document type's CURRENT schema - a field removed
-- since is dropped, a field made mandatory since is added - so a template
-- never pins a schema version. Defaults are applied when a filled-in
-- template is uploaded (BulkImportServiceImpl#createBatch), so the import
-- worker sees ordinary rows.
--
--   columns   [{"fieldKey": "invoice_number", "header": "Invoice No"}, ...]
--             filename/title are always the first two columns and are not
--             listed; header null = the field's label
--   defaults  {"currency": "LKR", ...} - raw cell text, converted and
--             validated like any cell
--
-- Conventions mirrored from V43: uuid PK, bare FK columns with an index,
-- VARCHAR + CHECK rather than enums, soft delete. Names are unique per
-- document type among live templates.

CREATE TABLE import_templates (
    import_template_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (tenant_id),
    document_type_id    UUID NOT NULL,
    name                VARCHAR(150) NOT NULL,
    description         VARCHAR(500),
    columns             JSONB NOT NULL DEFAULT '[]'::jsonb,
    defaults            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by          UUID REFERENCES users (user_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID REFERENCES users (user_id),
    updated_at          TIMESTAMPTZ,
    is_deleted          BOOLEAN NOT NULL DEFAULT false,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT fk_import_templates_document_type FOREIGN KEY (tenant_id, document_type_id)
        REFERENCES document_types (tenant_id, document_type_id),
    CONSTRAINT ck_import_templates_columns_array CHECK (jsonb_typeof(columns) = 'array'),
    CONSTRAINT ck_import_templates_defaults_object CHECK (jsonb_typeof(defaults) = 'object')
);

CREATE INDEX ix_import_templates_tenant_type ON import_templates (tenant_id, document_type_id);
CREATE UNIQUE INDEX uq_import_templates_live_name
    ON import_templates (tenant_id, document_type_id, lower(name)) WHERE NOT is_deleted;

-- Which saved template a batch was uploaded with (null: the standard one).
ALTER TABLE import_batches
    ADD COLUMN import_template_id UUID NULL REFERENCES import_templates (import_template_id);

CREATE INDEX ix_import_batches_import_template_id ON import_batches (import_template_id);
