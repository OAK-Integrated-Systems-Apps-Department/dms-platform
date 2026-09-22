-- Selectable OCR field extraction (My Documents upload page): the caller now
-- chooses which of a document type's schema fields OCR should attempt to
-- extract, instead of ocr-worker always running its full extraction pass.
--
-- requested_fields records what was actually asked for on a given version
-- (needed to support "re-run all selected fields" without the client having
-- to resend the original selection, and so a poller can tell "0 fields
-- requested" apart from "still waiting").
ALTER TABLE document_versions
    ADD COLUMN requested_fields JSONB NOT NULL DEFAULT '[]'::jsonb;

-- document_ocr_field_results is ocr-worker's own record of the latest
-- extraction attempt per (document, field_key) -- written directly by
-- ocr-worker (same direct-Postgres-access pattern this service already uses
-- for document_versions.ocr_status and ocr_outputs; see backend/workers/
-- ocr-worker/README.md "Database access"), read-only from dms-core's side.
-- It is deliberately separate from document_metadata_values: this table is
-- "what OCR last saw for this field" (including a not_found/low_confidence
-- attempt that never became a stored value), while document_metadata_values
-- is "the document's actual, editable field value" (which may be a manual
-- override OCR must never clobber -- see DocumentMetadataServiceImpl).
--
-- One row per (document_id, field_key); a re-run replaces the row in place
-- rather than accumulating history, matching document_metadata_values'
-- own "current state, not an audit log" shape.
CREATE TABLE document_ocr_field_results (
    field_result_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    version_id UUID NOT NULL REFERENCES document_versions (version_id),
    field_key VARCHAR(100) NOT NULL,
    value TEXT,
    confidence NUMERIC(4,3),
    status VARCHAR(20) NOT NULL,
    source_page INT,
    source_bbox JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_document_ocr_field_results_doc_field UNIQUE (document_id, field_key),
    CONSTRAINT ck_document_ocr_field_results_status CHECK (status IN ('extracted', 'not_found', 'low_confidence')),
    CONSTRAINT ck_document_ocr_field_results_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE INDEX ix_document_ocr_field_results_document_id ON document_ocr_field_results (document_id);
CREATE INDEX ix_document_ocr_field_results_version_id ON document_ocr_field_results (version_id);
CREATE INDEX ix_document_ocr_field_results_tenant_id ON document_ocr_field_results (tenant_id);
