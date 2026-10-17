-- Oak DMS OCR & Scanning (Section 2.4) -- zone/structured-field templates.
-- Proposed by ocr-worker: OS-FR-003 (zone OCR) and OS-FR-005 (structured
-- field extraction) both need a per-document-type template, and nothing in
-- V4/V5 defines one. One row per named zone; the zone's field_name IS its
-- structured-field mapping, so this single table covers both requirements
-- rather than needing a second "field template" table.
--
-- Coordinates are fractions of page width/height (0.0-1.0), not pixels, so
-- a template defined once applies regardless of the scan resolution a
-- given version was produced at.

CREATE TABLE document_type_ocr_zones (
    document_type_ocr_zone_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    document_type_id UUID NOT NULL REFERENCES document_types (document_type_id),
    zone_name VARCHAR(100) NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    page_number INT NOT NULL DEFAULT 0,
    x0 NUMERIC(6,5) NOT NULL,
    y0 NUMERIC(6,5) NOT NULL,
    x1 NUMERIC(6,5) NOT NULL,
    y1 NUMERIC(6,5) NOT NULL,
    created_by UUID REFERENCES users (user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users (user_id),
    updated_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_document_type_ocr_zones UNIQUE (document_type_id, zone_name),
    CONSTRAINT ck_document_type_ocr_zones_coords CHECK (
        x0 >= 0 AND x0 < x1 AND x1 <= 1 AND
        y0 >= 0 AND y0 < y1 AND y1 <= 1
    )
);

CREATE INDEX ix_document_type_ocr_zones_document_type_id ON document_type_ocr_zones (document_type_id);
CREATE INDEX ix_document_type_ocr_zones_tenant_id ON document_type_ocr_zones (tenant_id);
