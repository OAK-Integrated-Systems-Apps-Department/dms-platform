-- OCR label hints ("label aliases"): the words a catalog field may appear
-- as on a scanned document ("Inv #", "PO Number", "Billed By", ...), which
-- ocr-worker's rule-based label matcher searches for when extracting that
-- field. Until now they lived in a hardcoded Python dict (_LABEL_ALIASES in
-- backend/workers/ocr-worker/src/ocr_worker/extraction.py) keyed by
-- field_key, so a tenant-defined field could never be found by its label
-- without a code change and a worker redeploy. This table moves that
-- vocabulary into each tenant's own data; V41 seeds it with the old dict
-- verbatim, so extraction behaves exactly as before on day one. dms-core
-- owns every write (MetadataFieldLabelAliasService); ocr-worker only reads
-- it, one query per document, via the same direct-Postgres-access pattern
-- it already uses for document_ocr_field_results below (see
-- backend/workers/ocr-worker/README.md "Database access").
--
-- Why a child table and not a column on document_type_fields: that table
-- is versioned - every publish (DocumentTypeFieldServiceImpl#replaceMapping)
-- archives the current rows and inserts a fresh set at schema_version + 1,
-- copying forward ONLY what the publish request itself carries. Anything a
-- client doesn't resend is silently dropped, and that already happens in
-- practice: the frontend's mapping-matrix save sends only fieldId/
-- mandatory/displayOrder, wiping field_group and default_value_override on
-- every publish. Hints stored there would be erased the same way by any
-- screen that doesn't know about them, and saving a hint would force a
-- whole new schema version for something that isn't part of the form
-- schema at all. So hints live beside the mapping, keyed by (field,
-- optional document type), independent of schema_version.
--
-- Why one row per hint rather than an array/JSON list column:
-- display_order gives each hint an explicit match priority (the matcher
-- tries hints in order, most specific first - "total amount" must be tried
-- before a bare "amount"); uq_mfla_field_scope_alias below enforces
-- case-insensitive uniqueness per (field, scope), which no array column
-- can; nothing in this codebase maps a Postgres array or a JSON list onto
-- an entity field today; and ocr-worker's repository tests run its
-- SQLAlchemy models against SQLite (create_all), which has no ARRAY type.
--
-- Why document_type_id is optional: NULL means "global" - the hint applies
-- to the field in every document type it is mapped into (the old dict's
-- only behaviour). A non-null value scopes the hint to one document type,
-- because some hints are only safe in context: a bare "Number" is exactly
-- the purchase-order number on a Purchase Order, but on an Invoice it is
-- just as likely to be the invoice number. The matcher tries a field's
-- hints scoped to the document's own type first, then its global ones, and
-- ignores hints scoped to any other type.
--
-- Why no is_deleted/deleted_at: a (field, scope) hint list is always
-- replaced wholesale - hard delete + reinsert in one transaction, the same
-- "current state, not history" shape as document_metadata_values'
-- writeValues - and a soft-deleted row would only collide with the unique
-- index below. Why ON DELETE CASCADE on both composite FKs: metadata_fields
-- rows are hard-deleted (MetadataFieldServiceImpl#deleteField), and
-- metadata_field_options/metadata_field_validations, whose FKs have no
-- CASCADE, already block that delete with a generic 409 today - hints are
-- meaningless without their field (or their document type) and must not
-- become a third blocker. The FKs are composite on (tenant_id, ...), like
-- document_type_fields' own, so one tenant's hint can never point at
-- another tenant's field or document type.
--
-- document_ocr_field_results.match_basis records how ocr-worker found each
-- field's value - or, for a not_found result, what it searched for - so the
-- upload form can explain a result instead of hiding a silent fallback:
--   label_hint - matched through one of the field's configured hints
--                (not_found: none of those hints was on the document)
--   field_name - the field has no hints configured, so the matcher searched
--                for the field's own label (e.g. "PO Date") instead
--                (not_found: that label wasn't on the document either)
--   zone       - read from an OCR template zone drawn for the document type
--   pattern    - found by a value-shape pattern alone, with no label
--                (a bare date, the reference-number regex)
--   generated  - derived from the text rather than read off a label
--                (the free-text summary/keywords fallback, language detection)
-- NULL on rows written before this migration, and on the legacy
-- (non-field-selection) extraction path, which never writes this table.
CREATE TABLE metadata_field_label_aliases (
    label_alias_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenants (tenant_id),
    field_id          UUID NOT NULL,
    document_type_id  UUID,                 -- NULL = every document type this field is mapped into
    alias_text        VARCHAR(100) NOT NULL,
    display_order     INT NOT NULL,         -- match priority within (field, scope); lower = tried first
    created_by        UUID REFERENCES users (user_id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by        UUID REFERENCES users (user_id),
    updated_at        TIMESTAMPTZ,
    CONSTRAINT fk_mfla_tenant_field FOREIGN KEY (tenant_id, field_id)
        REFERENCES metadata_fields (tenant_id, field_id) ON DELETE CASCADE,
    CONSTRAINT fk_mfla_tenant_document_type FOREIGN KEY (tenant_id, document_type_id)
        REFERENCES document_types (tenant_id, document_type_id) ON DELETE CASCADE,
    CONSTRAINT ck_mfla_alias_text CHECK (
        alias_text = btrim(alias_text) AND char_length(alias_text) >= 2 AND alias_text ~ '[[:alnum:]]')
);
CREATE INDEX ix_mfla_tenant_id        ON metadata_field_label_aliases (tenant_id);
CREATE INDEX ix_mfla_field_id         ON metadata_field_label_aliases (field_id);
CREATE INDEX ix_mfla_document_type_id ON metadata_field_label_aliases (document_type_id);
-- COALESCE folds every global (NULL-scoped) row onto one sentinel scope, so
-- "Invoice No" and "invoice no" can't both exist as global hints of the
-- same field - a plain (field_id, document_type_id, ...) index would treat
-- each NULL as distinct and let the duplicate through.
CREATE UNIQUE INDEX uq_mfla_field_scope_alias ON metadata_field_label_aliases
    (field_id, COALESCE(document_type_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(alias_text));

ALTER TABLE document_ocr_field_results ADD COLUMN match_basis VARCHAR(30);
ALTER TABLE document_ocr_field_results ADD CONSTRAINT ck_document_ocr_field_results_match_basis
    CHECK (match_basis IS NULL OR match_basis IN ('label_hint', 'field_name', 'zone', 'pattern', 'generated'));
