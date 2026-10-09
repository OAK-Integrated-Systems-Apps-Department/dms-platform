-- Phase 3 (Extraction): invoice line items, and the raw OCR string behind
-- every normalized field value.
--
-- Production prompt 3.8 asks for both: "invoice line items (description,
-- quantity, unit price, line total)" and "store the raw OCR string
-- alongside every normalized value".

-- ---------------------------------------------------------------------
-- 1. raw_value on document_ocr_field_results
-- ---------------------------------------------------------------------
-- `value` is normalized for a date field: the page prints "10/08/2026"
-- and we store "2026-08-10". That normalisation is exactly where
-- day-first/month-first ambiguity is decided, and until now the evidence
-- for that decision was discarded -- a reviewer looking at "2026-08-10"
-- had no way to see what the document actually said, which is the one
-- thing they need in order to judge whether it was read correctly.
--
-- Nullable rather than NOT NULL DEFAULT '': existing rows genuinely do
-- not have this, and back-filling them with a copy of `value` would
-- assert something untrue about where that string came from.
ALTER TABLE document_ocr_field_results
    ADD COLUMN raw_value TEXT;

COMMENT ON COLUMN document_ocr_field_results.raw_value IS
    'The string as read off the page, before normalisation. Equal to value '
    'for fields that are not normalized; differs for dates, where value is '
    'ISO. NULL on rows written before Phase 3.';

-- ---------------------------------------------------------------------
-- 2. document_ocr_line_items
-- ---------------------------------------------------------------------
-- DECISION: a real table, not a JSONB column on document_versions.
--
-- The JSONB option is genuinely attractive and was the near-miss. Line
-- items belong to exactly one version, nothing queries across documents
-- by line description, and the full OCR output (including the raw table
-- grid) already goes to MinIO as one JSON artifact -- so a column would
-- have matched the existing "the artifact is the source of truth" shape
-- and cost no migration at all.
--
-- It loses on one requirement, and it is the one that matters:
-- **corrections**. Phase 4 captures user corrections against extracted
-- values, and the review UI's whole job is to let someone fix line 3's
-- quantity. In a JSONB blob that edit has no identity -- there is no row
-- to reference, no per-line audit entry, and a concurrent correction to
-- line 1 and line 3 is a lost update, because both rewrite the same
-- document. With a row per line, a correction is an UPDATE on a known
-- primary key, and this repo already has an audit module that works in
-- those terms.
--
-- Secondary, but real: typed NUMERIC columns with CHECK constraints mean
-- a malformed amount fails loudly at write time instead of being stored
-- as the string "1,2 34.00" inside a blob and discovered much later. The
-- 3.9 rules (quantity x unit_price = line_total, sum(line_total) =
-- subtotal) are arithmetic over these values, and arithmetic over text
-- pulled out of JSON is how rounding bugs start.
--
-- Cost accepted: one more table, and a delete-then-insert on every re-run
-- (below) rather than a single column overwrite.
CREATE TABLE document_ocr_line_items (
    line_item_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    version_id UUID NOT NULL REFERENCES document_versions (version_id) ON DELETE CASCADE,

    -- 0-based position as read down the page. Ordering is part of the
    -- data: "the third line" is how a human refers to one, and a set with
    -- no order cannot be shown back to them the way the invoice printed it.
    line_number INT NOT NULL,

    description TEXT,
    quantity NUMERIC(18, 4),
    unit_price NUMERIC(18, 4),
    line_total NUMERIC(18, 4),

    -- The cells exactly as OCR read them, before parsing to NUMERIC.
    -- Same reasoning as raw_value above: the parse is where information is
    -- lost, so the input to it is what a reviewer needs.
    raw_cells JSONB,

    -- Per-line confidence, computed the same way as a field's
    -- (field_confidence.py). A line whose quantity was read at 0.4 is not
    -- the same as one read at 0.99, and a single document-level number
    -- cannot say which line to look at.
    confidence NUMERIC(4, 3),

    -- Which page the line came from, so the review UI can jump to it.
    source_page INT,
    source_bbox JSONB,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,

    CONSTRAINT uq_document_ocr_line_items_version_line UNIQUE (version_id, line_number),
    CONSTRAINT ck_document_ocr_line_items_line_number CHECK (line_number >= 0),
    CONSTRAINT ck_document_ocr_line_items_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    -- Quantity may legitimately be negative (a credit line), so only the
    -- nonsensical case is rejected.
    CONSTRAINT ck_document_ocr_line_items_unit_price
        CHECK (unit_price IS NULL OR unit_price >= 0)
);

-- The read pattern is "every line for this version, in order", which is
-- what the review UI and the 3.9 sum-check both want.
CREATE INDEX ix_document_ocr_line_items_version
    ON document_ocr_line_items (version_id, line_number);
CREATE INDEX ix_document_ocr_line_items_document_id
    ON document_ocr_line_items (document_id);
CREATE INDEX ix_document_ocr_line_items_tenant_id
    ON document_ocr_line_items (tenant_id);

COMMENT ON TABLE document_ocr_line_items IS
    'Invoice line items extracted by ocr-worker (production prompt 3.8). '
    'One row per line per version; a re-run replaces this version''s rows '
    'wholesale rather than accumulating history, matching '
    'document_ocr_field_results'' own current-state shape.';
