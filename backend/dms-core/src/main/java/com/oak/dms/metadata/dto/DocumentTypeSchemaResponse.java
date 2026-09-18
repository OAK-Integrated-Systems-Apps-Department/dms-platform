package com.oak.dms.metadata.dto;

import java.util.List;
import java.util.UUID;

/**
 * The fully resolved form schema for a document type at a given version -
 * fields, labels, types, mandatory flags, options, order, and validation
 * rules in one payload (MDTM-FR-006), for the upload form and for reuse by
 * import, OCR, search, and workflow once those modules exist.
 */
public record DocumentTypeSchemaResponse(
        UUID documentTypeId,
        String typeCode,
        String typeName,
        int schemaVersion,
        List<SchemaField> fields
) {
    public record SchemaField(
            UUID fieldId,
            String fieldKey,
            String label,
            String helpText,
            String dataType,
            String status,
            boolean mandatory,
            boolean hidden,
            int displayOrder,
            String fieldGroup,
            String defaultValue,
            List<MetadataFieldOptionResponse> options,
            List<MetadataFieldValidationResponse> validations
    ) {
    }
}
