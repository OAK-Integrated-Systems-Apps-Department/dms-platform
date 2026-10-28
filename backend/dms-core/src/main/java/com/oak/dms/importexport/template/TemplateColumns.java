package com.oak.dms.importexport.template;

import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;

import java.util.ArrayList;
import java.util.List;

/**
 * The layout every import template shares. Metadata columns are headed by
 * the field's {@code field_key}; the reserved columns contain spaces, which
 * a field key never can (lower_snake_case - see CreateMetadataFieldRequest),
 * so the two can never collide.
 */
public final class TemplateColumns {

    public static final String FILENAME = "File name";
    public static final String TITLE = "Document title";
    /** Only in a failed-rows report; ignored when a template is read back. */
    public static final String ERRORS = "Import errors";

    public static final String DATA_SHEET = "Documents";
    public static final String GUIDANCE_SHEET = "Guidance";
    /** Very hidden sheet carrying the version marker. */
    public static final String MARKER_SHEET = "_dms";

    /** First line of a CSV template: {@code # dms-import-template documentTypeId=... schemaVersion=...}. */
    public static final String CSV_MARKER = "# dms-import-template";

    private TemplateColumns() {
    }

    /**
     * The fields a template carries: active, visible ones, in form order.
     * Exactly the fields MetadataValidationService checks - hidden or
     * inactive fields are skipped there too.
     */
    public static List<SchemaField> importableFields(DocumentTypeSchemaResponse schema) {
        return schema.fields().stream()
                .filter(f -> "active".equals(f.status()) && !f.hidden())
                .toList();
    }

    public static List<String> headers(List<SchemaField> fields) {
        List<String> headers = new ArrayList<>();
        headers.add(FILENAME);
        headers.add(TITLE);
        fields.forEach(f -> headers.add(f.fieldKey()));
        return headers;
    }
}