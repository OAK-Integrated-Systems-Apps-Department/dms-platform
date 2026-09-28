package com.oak.dms.document;

import com.oak.dms.document.dto.DocumentMetadataResponse;

import java.util.Map;
import java.util.UUID;

/**
 * Reads/writes {@link DocumentMetadataValue} rows for a document, resolving
 * against its document type's current schema (module 2.7's {@code
 * DocumentTypeFieldService#getSchema}). The first real writer of {@code
 * document_metadata_values} -- see that table's own migration comment.
 */
public interface DocumentMetadataService {

    /**
     * @return the document's current field values plus which of them still
     * need human review (see {@link DocumentMetadataResponse}). A field
     * with no saved value is simply absent from {@code values}.
     */
    DocumentMetadataResponse getValues(UUID documentId);

    /**
     * Saves the given values with source="manual", one row (or ordinal
     * group, for multi_select) per field -- replacing whatever was
     * previously saved for each field key present in the map. A blank/null
     * value clears that field's rows instead of writing an empty one.
     * Unknown field keys (not in the document type's current schema) are
     * silently ignored, not rejected -- lets a caller send a superset a
     * schema no longer has without erroring.
     *
     * @throws com.oak.dms.common.exception.MetadataValidationFailedException
     * if enforceMandatory and any mandatory field ends up with no value
     * after this save (existing values from a previous save count).
     */
    void saveValues(UUID documentId, Map<String, Object> valuesByFieldKey, boolean enforceMandatory);

    /**
     * Best-effort fill from a completed OCR run's {@code fields} output
     * (ocr-worker's own output contract: {@code {fieldName: {value,
     * confidence, bbox}}}) -- only for fields the document type's schema
     * marks {@code ocrExtractable}, and only those that don't already have
     * a manually-saved value (manual entry always wins over OCR). Saves
     * with source="ocr" and the OCR field's own confidence score. Never
     * throws for a validation failure -- OCR filling in some, but not all,
     * mandatory fields is expected and shouldn't fail the OCR run itself.
     */
    void applyOcrFields(UUID documentId, Map<String, Object> ocrFieldsRaw);
}
