package com.oak.dms.importexport.dto;

/**
 * One problem with a failed import row, as reported to the user.
 *
 * @param field      the column it's about - {@code file}, {@code filename},
 *                   {@code title}, or a field_key; may be null
 * @param fieldLabel the column's display name - the schema field's label, or
 *                   the key itself if the field is no longer known
 * @param severity   {@code error} or {@code warning}
 */
public record ImportRowProblem(String field, String fieldLabel, String code, String message, String severity) {
}
