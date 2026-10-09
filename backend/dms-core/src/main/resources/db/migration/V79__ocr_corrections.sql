-- Phase 4d: what the reviewer decided, and what OCR had said.
--
-- "Store corrections (original OCR value vs. user value) so accuracy can
-- be measured from real usage" (production prompt 3.13).
--
-- The measurement is the point, and it is why this is its own table
-- rather than an UPDATE of document_ocr_field_results. Overwriting the
-- OCR value with the corrected one would make every screen look right
-- and destroy the only record of how often OCR was wrong -- which is the
-- number the eval corpus can only estimate and real usage can actually
-- measure. Both values have to survive, together, with the confidence
-- OCR had at the time.
--
-- ACCEPTANCES are recorded too, not just corrections. A table of only
-- corrections has no denominator: 40 corrections means nothing without
-- knowing whether 50 or 50,000 fields were looked at. `action` carries
-- that distinction so "extracted-status precision, as measured in
-- production" is a single query rather than an estimate.
CREATE TABLE document_ocr_corrections (
    correction_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_id UUID NOT NULL REFERENCES documents (document_id),
    version_id UUID NOT NULL REFERENCES document_versions (version_id) ON DELETE CASCADE,

    field_key VARCHAR(100) NOT NULL,

    -- 'accepted' -> the reviewer confirmed OCR was right.
    -- 'corrected' -> they replaced it.
    action VARCHAR(20) NOT NULL,

    -- How the corrected value was produced. 'typed' and 'region' have
    -- very different meanings for the accuracy question: a typed
    -- correction says the extraction was wrong, while a region one says
    -- OCR could read the text once pointed at the right part of the page,
    -- which is a localisation failure, not a recognition one. Collapsing
    -- them would hide which half of the pipeline to fix.
    source VARCHAR(20),

    -- What OCR said, exactly as it said it, plus how sure it was. Kept
    -- even for an acceptance: "accepted at 0.62" is the observation that
    -- says the low-confidence threshold is set too low.
    original_value TEXT,
    original_confidence NUMERIC(4, 3),
    original_status VARCHAR(30),

    -- What the reviewer settled on. Equal to original_value for an
    -- acceptance, written out rather than left NULL so a query for "the
    -- true value of this field" never has to branch on action.
    corrected_value TEXT,

    corrected_by UUID REFERENCES users (user_id),
    corrected_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_document_ocr_corrections_action
        CHECK (action IN ('accepted', 'corrected')),
    CONSTRAINT ck_document_ocr_corrections_source
        CHECK (source IS NULL OR source IN ('typed', 'region')),
    CONSTRAINT ck_document_ocr_corrections_confidence
        CHECK (original_confidence IS NULL
               OR (original_confidence >= 0 AND original_confidence <= 1))
);

-- Deliberately NOT unique on (version_id, field_key). A reviewer who
-- corrects a field, looks again and corrects it differently has produced
-- two observations, and the first one -- the value they rejected -- is
-- exactly the kind of thing worth keeping. This is an append-only log,
-- and "the current decision" is its newest row.
CREATE INDEX ix_document_ocr_corrections_version
    ON document_ocr_corrections (version_id, field_key, corrected_at DESC);

-- The measurement query's own access path: "every decision in this
-- tenant in this period", which is how the accuracy-from-real-usage
-- number gets computed.
CREATE INDEX ix_document_ocr_corrections_tenant_time
    ON document_ocr_corrections (tenant_id, corrected_at DESC);

COMMENT ON TABLE document_ocr_corrections IS
    'Append-only log of reviewer decisions on OCR field values (Phase 4d). '
    'Both the OCR value and the human value are kept so extraction accuracy '
    'can be measured from real usage rather than only from the eval corpus.';
