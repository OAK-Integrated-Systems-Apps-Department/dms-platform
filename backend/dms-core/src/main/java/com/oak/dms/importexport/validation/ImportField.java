package com.oak.dms.importexport.validation;

import java.util.UUID;

/**
 * One field of a document type at one schema version, as import row
 * validation and record creation need it - the catalogue's id, key, label
 * and data type joined with that version's mandatory/hidden flags. The id
 * is what {@code document_metadata.field_values} is keyed by. See
 * {@link ImportSchemaFields}.
 */
public record ImportField(UUID fieldId, String fieldKey, String label, String dataType, boolean mandatory,
                          boolean hidden) {
}
