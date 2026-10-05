package com.oak.dms.importexport.dto;

import java.util.List;
import java.util.UUID;

/**
 * What a bulk import into a document type would write as each document's
 * metadata right now, at the type's current schema version.
 *
 * @param defaults                      the default values that will be applied
 * @param mandatoryFieldsWithoutDefault mandatory fields with no usable default -
 *                                      left empty (with a warning) unless
 *                                      {@code enforceMandatoryFields}, when
 *                                      the import can't be started
 * @param enforceMandatoryFields        {@code dms.import-export.enforce-mandatory-fields}
 */
public record ImportDefaultsResponse(
        UUID documentTypeId,
        int schemaVersion,
        List<DefaultValue> defaults,
        List<Field> mandatoryFieldsWithoutDefault,
        boolean enforceMandatoryFields
) {

    /** @param value the default as configured, before conversion */
    public record DefaultValue(String fieldKey, String label, String value) {
    }

    public record Field(String fieldKey, String label) {
    }
}
