package com.oak.dms.importexport.validation;

/**
 * One field of a document type at one schema version, as import row
 * validation needs it - the catalogue's key, label and data type joined
 * with that version's mandatory/hidden flags. See {@link ImportSchemaFields}.
 */
public record ImportField(String fieldKey, String label, String dataType, boolean mandatory, boolean hidden) {
}
