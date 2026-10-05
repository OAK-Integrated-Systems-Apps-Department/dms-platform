package com.oak.dms.importexport.validation;

import java.util.UUID;

/**
 * One field of a document type at one schema version, as import validation
 * and record creation need it - the catalogue's id, key, label and data type
 * joined with that version's mandatory/hidden flags and default value. The
 * id is what {@code document_metadata.field_values} is keyed by. See
 * {@link ImportSchemaFields}.
 *
 * @param defaultValue the value every imported document gets for this
 *                     field: the version's per-type override if set, else
 *                     the field's own default; null if neither is
 */
public record ImportField(UUID fieldId, String fieldKey, String label, String dataType, boolean mandatory,
                          boolean hidden, String defaultValue) {
}
