package com.oak.dms.metadata.service;

import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.metadata.entity.MetadataFieldValidation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reusable validation engine (A4): given a document type and a map of
 * submitted field values (keyed by {@code field_key}), returns every
 * validation failure. Mandatory-ness always comes from the mapping's
 * {@code mandatory} flag in {@code document_type_schemas.fields}, never from a rule in
 * {@link MetadataFieldValidation} - there is deliberately no "required" rule
 * type. Intended for reuse by the future upload form (A11), import, OCR,
 * search, and workflow modules once they exist - nothing in this module
 * calls it yet.
 */
public interface MetadataValidationService {

    /**
     * @param schemaVersion null to validate against the document type's
     *                      current version; a specific version to validate
     *                      against a historical schema (e.g. re-validating
     *                      an existing document without moving it forward).
     */
    List<ValidationError> validate(UUID documentTypeId, Integer schemaVersion, Map<String, Object> fieldValuesByKey);
}
