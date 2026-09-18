-- Module 2.7 - Metadata & Document-Type Management (MDTM).
--
-- Six tables, matching the confirmed ER diagram exactly:
--   metadata_fields             - the centralized field catalog (MDTM-FR-001)
--   metadata_field_options       - dropdown/multi_select option lists, with
--                                  parent_option_id for cascading lists
--   metadata_field_validations   - per-field validation rules (MDTM-FR-004)
--   document_types                - content types (Contract, Invoice, ...) (MDTM-FR-002)
--   document_type_fields          - the versioned type<->field mapping (MDTM-FR-005)
--   document_metadata_values      - per-document field values; document_id is
--                                  deliberately unconstrained (see below), and
--                                  this module writes nothing into it - it
--                                  exists for module 2.5 / A11 to use later.
--
-- Conventions mirrored from V1/V5/V13: uuid PK DEFAULT gen_random_uuid(),
-- tenant_id UUID NOT NULL REFERENCES tenants + ix_<table>_tenant_id, the
-- full audit block with created_by/updated_by as bare UUID REFERENCES
-- users (no JPA relation), status as VARCHAR + CHECK (never a Postgres
-- enum), and CREATE UNIQUE INDEX ... WHERE <predicate> for tenant-scoped
-- uniqueness among non-deleted rows (the same "partial index over a
-- constraint" idiom V13 uses for tenant_id IS NULL/NOT NULL).

-- =====================================================================
-- metadata_fields
-- =====================================================================
CREATE TABLE metadata_fields (
    field_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (tenant_id),
    field_key           VARCHAR(100) NOT NULL,
    label               VARCHAR(150) NOT NULL,
    help_text           VARCHAR(500),
    data_type           VARCHAR(30) NOT NULL,
    default_value       VARCHAR(500),
    is_system_field     BOOLEAN NOT NULL DEFAULT false,
    is_searchable       BOOLEAN NOT NULL DEFAULT true,
    is_indexed          BOOLEAN NOT NULL DEFAULT false,
    is_ocr_extractable  BOOLEAN NOT NULL DEFAULT false,
    status              VARCHAR(30) NOT NULL DEFAULT 'active',
    created_by          UUID REFERENCES users (user_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID REFERENCES users (user_id),
    updated_at          TIMESTAMPTZ,
    is_deleted          BOOLEAN NOT NULL DEFAULT false,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT ck_metadata_fields_data_type CHECK (data_type IN
        ('text', 'long_text', 'number', 'decimal', 'date', 'datetime',
         'boolean', 'dropdown', 'multi_select', 'user_ref', 'lookup')),
    CONSTRAINT ck_metadata_fields_status CHECK (status IN ('active', 'inactive', 'deprecated')),
    -- Support constraint for the composite FK from document_type_fields
    -- below - Postgres requires the referenced columns to be covered by a
    -- unique constraint/index, even though field_id alone is already the PK.
    CONSTRAINT uq_metadata_fields_tenant_field_id UNIQUE (tenant_id, field_id)
);

CREATE INDEX ix_metadata_fields_tenant_id ON metadata_fields (tenant_id);

-- field_key unique per tenant, among non-deleted rows only.
CREATE UNIQUE INDEX uq_metadata_fields_tenant_field_key
    ON metadata_fields (tenant_id, field_key)
    WHERE is_deleted = false;

-- =====================================================================
-- metadata_field_options
-- =====================================================================
CREATE TABLE metadata_field_options (
    option_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants (tenant_id),
    field_id            UUID NOT NULL REFERENCES metadata_fields (field_id),
    parent_option_id    UUID REFERENCES metadata_field_options (option_id),
    option_value        VARCHAR(150) NOT NULL,
    option_label        VARCHAR(150) NOT NULL,
    display_order       INT NOT NULL DEFAULT 0,
    status              VARCHAR(30) NOT NULL DEFAULT 'active',
    created_by          UUID REFERENCES users (user_id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID REFERENCES users (user_id),
    updated_at          TIMESTAMPTZ,
    is_deleted          BOOLEAN NOT NULL DEFAULT false,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT ck_metadata_field_options_status CHECK (status IN ('active', 'inactive'))
);

CREATE INDEX ix_metadata_field_options_tenant_id ON metadata_field_options (tenant_id);
CREATE INDEX ix_metadata_field_options_field_id ON metadata_field_options (field_id);
CREATE INDEX ix_metadata_field_options_parent_option_id ON metadata_field_options (parent_option_id);

-- =====================================================================
-- metadata_field_validations
-- =====================================================================
CREATE TABLE metadata_field_validations (
    validation_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants (tenant_id),
    field_id             UUID NOT NULL REFERENCES metadata_fields (field_id),
    rule_type            VARCHAR(30) NOT NULL,
    rule_value           VARCHAR(500),
    error_message        VARCHAR(500),
    severity             VARCHAR(20) NOT NULL DEFAULT 'error',
    created_by           UUID REFERENCES users (user_id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by           UUID REFERENCES users (user_id),
    updated_at           TIMESTAMPTZ,
    is_deleted           BOOLEAN NOT NULL DEFAULT false,
    deleted_at           TIMESTAMPTZ,
    -- No 'required' rule type: mandatory-ness always comes from
    -- document_type_fields.is_mandatory, never a validation rule.
    CONSTRAINT ck_metadata_field_validations_rule_type CHECK (rule_type IN
        ('min_length', 'max_length', 'min_value', 'max_value', 'regex',
         'date_format', 'date_range', 'allowed_values')),
    CONSTRAINT ck_metadata_field_validations_severity CHECK (severity IN ('error', 'warning'))
);

CREATE INDEX ix_metadata_field_validations_tenant_id ON metadata_field_validations (tenant_id);
CREATE INDEX ix_metadata_field_validations_field_id ON metadata_field_validations (field_id);

-- =====================================================================
-- document_types
-- =====================================================================
CREATE TABLE document_types (
    document_type_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   UUID NOT NULL REFERENCES tenants (tenant_id),
    type_code                   VARCHAR(50) NOT NULL,
    type_name                   VARCHAR(150) NOT NULL,
    description                 VARCHAR(500),
    icon                        VARCHAR(50),
    display_order               INT NOT NULL DEFAULT 0,
    current_schema_version      INT NOT NULL DEFAULT 1,
    -- Module 6.1 (Records Retention & Compliance Management) does not exist
    -- yet, so this is a plain UUID with no FK - it becomes a real reference
    -- once that module's retention_policies table exists.
    default_retention_policy_id UUID,
    requires_watermark          BOOLEAN NOT NULL DEFAULT false,
    coediting_enabled           BOOLEAN NOT NULL DEFAULT false,
    status                      VARCHAR(30) NOT NULL DEFAULT 'active',
    created_by                  UUID REFERENCES users (user_id),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                  UUID REFERENCES users (user_id),
    updated_at                  TIMESTAMPTZ,
    is_deleted                  BOOLEAN NOT NULL DEFAULT false,
    deleted_at                  TIMESTAMPTZ,
    CONSTRAINT ck_document_types_status CHECK (status IN ('active', 'inactive')),
    -- Support constraint for the composite FK from document_type_fields below.
    CONSTRAINT uq_document_types_tenant_document_type_id UNIQUE (tenant_id, document_type_id)
);

CREATE INDEX ix_document_types_tenant_id ON document_types (tenant_id);

-- type_code unique per tenant, among non-deleted rows only.
CREATE UNIQUE INDEX uq_document_types_tenant_type_code
    ON document_types (tenant_id, type_code)
    WHERE is_deleted = false;

-- =====================================================================
-- document_type_fields  (versioned type <-> field mapping)
-- =====================================================================
CREATE TABLE document_type_fields (
    document_type_field_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID NOT NULL REFERENCES tenants (tenant_id),
    document_type_id         UUID NOT NULL REFERENCES document_types (document_type_id),
    field_id                 UUID NOT NULL REFERENCES metadata_fields (field_id),
    schema_version            INT NOT NULL,
    status                    VARCHAR(30) NOT NULL DEFAULT 'active',
    is_mandatory              BOOLEAN NOT NULL DEFAULT false,
    is_readonly               BOOLEAN NOT NULL DEFAULT false,
    is_hidden                 BOOLEAN NOT NULL DEFAULT false,
    -- Set true when a PUT /document-types/{id}/fields publish makes a field
    -- newly mandatory (MDTM workflow step 7 / alternate flow 3), so legacy
    -- documents can be flagged for optional backfill rather than rejected.
    requires_backfill         BOOLEAN NOT NULL DEFAULT false,
    display_order             INT NOT NULL DEFAULT 0,
    field_group                VARCHAR(100),
    default_value_override    VARCHAR(500),
    archived_at                TIMESTAMPTZ,
    created_by                 UUID REFERENCES users (user_id),
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                 UUID REFERENCES users (user_id),
    updated_at                 TIMESTAMPTZ,
    is_deleted                 BOOLEAN NOT NULL DEFAULT false,
    deleted_at                 TIMESTAMPTZ,
    CONSTRAINT ck_document_type_fields_status CHECK (status IN ('active', 'archived')),
    CONSTRAINT uq_document_type_fields_type_field_version
        UNIQUE (document_type_id, field_id, schema_version),
    -- Composite FKs so one tenant's field can never be mapped into another
    -- tenant's document type.
    CONSTRAINT fk_document_type_fields_tenant_field
        FOREIGN KEY (tenant_id, field_id) REFERENCES metadata_fields (tenant_id, field_id),
    CONSTRAINT fk_document_type_fields_tenant_type
        FOREIGN KEY (tenant_id, document_type_id) REFERENCES document_types (tenant_id, document_type_id)
);

CREATE INDEX ix_document_type_fields_tenant_id ON document_type_fields (tenant_id);
CREATE INDEX ix_document_type_fields_document_type_id ON document_type_fields (document_type_id);
CREATE INDEX ix_document_type_fields_field_id ON document_type_fields (field_id);

-- =====================================================================
-- document_metadata_values
-- =====================================================================
CREATE TABLE document_metadata_values (
    metadata_value_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants (tenant_id),
    -- TODO(module 2.5): FK to documents(document_id) once the documents
    -- table exists. Left unconstrained deliberately - this module's scope
    -- ends before module 2.5, and this table must exist now so 2.5/A11 can
    -- write into it later without another migration.
    document_id          UUID NOT NULL,
    field_id              UUID NOT NULL REFERENCES metadata_fields (field_id),
    value_ordinal          INT NOT NULL DEFAULT 0,
    value_text             TEXT,
    value_number            NUMERIC,
    value_date               TIMESTAMPTZ,
    value_boolean             BOOLEAN,
    -- Soft reference: users(user_id) for data_type 'user_ref', or a
    -- metadata_field_options(option_id)-style lookup row for 'lookup' -
    -- deliberately no FK constraint, validated in the service layer since
    -- which table it points at depends on the field's data_type.
    value_ref_id               UUID,
    source                     VARCHAR(30) NOT NULL DEFAULT 'manual',
    confidence_score             NUMERIC(5, 4),
    created_by                   UUID REFERENCES users (user_id),
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                   UUID REFERENCES users (user_id),
    updated_at                   TIMESTAMPTZ,
    is_deleted                   BOOLEAN NOT NULL DEFAULT false,
    deleted_at                   TIMESTAMPTZ,
    CONSTRAINT ck_document_metadata_values_source CHECK (source IN
        ('manual', 'ocr', 'import', 'migration', 'integration')),
    -- Exactly one value_* column may be populated per row.
    CONSTRAINT ck_document_metadata_values_single_value CHECK (
        (CASE WHEN value_text IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN value_number IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN value_date IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN value_boolean IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN value_ref_id IS NOT NULL THEN 1 ELSE 0 END) = 1
    ),
    -- The ordinal is what makes multi-select fields possible: one row per
    -- selected value, ordered by value_ordinal.
    CONSTRAINT uq_document_metadata_values_doc_field_ordinal
        UNIQUE (document_id, field_id, value_ordinal)
);

CREATE INDEX ix_document_metadata_values_tenant_id ON document_metadata_values (tenant_id);
CREATE INDEX ix_document_metadata_values_document_id ON document_metadata_values (document_id);
CREATE INDEX ix_document_metadata_values_field_id ON document_metadata_values (field_id);
