-- Confidence-gating for OCR-sourced metadata values (see
-- com.oak.dms.document.DocumentMetadataValue#needsReview and
-- DocumentMetadataServiceImpl#applyOcrFields). A low-confidence OCR
-- extraction (ocr-worker's RequestedFieldResult.status == "low_confidence")
-- is still written to document_metadata_values rather than silently
-- dropped, but flagged here so the frontend can visually distinguish it
-- from a confident extraction instead of presenting both identically.
-- Always false for source='manual' - a human editing/confirming a field is
-- exactly what should clear this, and writeValues' delete-then-reinsert
-- pattern means a later write (manual or a confident OCR re-run) clears a
-- stale flag automatically, with no explicit "clear" step needed anywhere.
ALTER TABLE document_metadata_values
    ADD COLUMN needs_review BOOLEAN NOT NULL DEFAULT false;
