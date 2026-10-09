-- Which seeded metadata fields OCR is expected to read off the document.
--
-- `metadata_fields.is_ocr_extractable` has existed since V28 and defaults
-- to false, but nothing ever seeded it and nothing read it: the comment in
-- DocumentMetadataServiceImpl records it being abandoned as a gate when
-- selectable OCR fields arrived. This migration gives it its meaning back.
--
-- The question it answers is NOT "is this field required" -- that is
-- `document_type_fields.is_mandatory` and is untouched here. It is:
--
--     is this value PRINTED on the document somebody sent us, or is it
--     something THIS organisation assigns after receiving it?
--
-- The distinction matters because `requires_review` fires when a mandatory
-- field is not extracted. Measured on the real evaluation corpus
-- (eval/reports/fix7-mandatory.md): `classification_level` is mandatory
-- for INVOICE and no supplier invoice on earth prints one, so it was
-- reported missing on 20 of 21 real documents and put every single one of
-- them in the review queue. A queue containing everything is the problem
-- the queue was built to solve.
--
-- Every field is set explicitly, including the ones that end up true, so
-- nothing here depends on V28's column default.

UPDATE metadata_fields SET is_ocr_extractable = true
WHERE is_deleted = false AND field_key IN (
    -- Invoice/receipt figures and identifiers, printed by the issuer
    'invoice_number', 'invoice_date', 'amount', 'currency',
    'vendor_supplier_name', 'purchase_order_number',
    -- Contract terms, printed on the contract
    'contract_party', 'contract_value', 'contract_start_date', 'contract_end_date',
    -- Dates and references a document states about itself
    'effective_date', 'expiry_date', 'review_due_date', 'reference_number',
    -- Stated on the document: "Author:" / "Prepared by:" on a policy or
    -- report, an employee id on an HR document
    'author', 'employee_id',
    -- Detected from the content itself rather than read off a label, but
    -- still a property of the document and not an assignment by us
    'language'
);

UPDATE metadata_fields SET is_ocr_extractable = false
WHERE is_deleted = false AND field_key IN (
    -- Governance decisions made by the receiving organisation. A vendor's
    -- invoice does not carry our sensitivity classification, our retention
    -- schedule, our department, our cost centre or our project code --
    -- these are chosen at filing time, from this tenant's own option lists.
    'classification_level',
    'retention_class',
    'department',
    'cost_centre',
    'project_code',
    -- Generated FROM the document, not read OFF it. extraction.py's
    -- free-text fallback writes a summary and a keyword list when no label
    -- matches, which is a useful suggestion and is not extraction. Scoring
    -- it as extraction is what produced the evaluation corpus's most
    -- misleading number: `description` and `tags` were 42 of 136 labelled
    -- fields and scored 42/42, because the labels had been pre-filled with
    -- the pipeline's own output and never corrected. Against corrected
    -- labels they score 0/21 and 1/21.
    --
    -- NOTE for whoever revisits this: turning these off stops the
    -- suggestion being produced at all. If the suggestion is wanted in the
    -- UI, it should come back as its own status (a "suggested" value a
    -- reviewer accepts) rather than as an OCR field result, so it is never
    -- counted as accuracy again.
    'description',
    'tags',
    'keywords'
);
