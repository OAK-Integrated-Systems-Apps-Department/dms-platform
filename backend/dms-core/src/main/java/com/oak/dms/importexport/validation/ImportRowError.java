package com.oak.dms.importexport.validation;

/**
 * One problem found with an import row, as stored in
 * {@code import_items.validation_errors} (a JSON array of these).
 *
 * @param field    what it's about - {@code file}, {@code title}, a field_key,
 *                 or null
 * @param severity {@code error} fails the row; {@code warning} is only reported
 */
public record ImportRowError(String field, String code, String message, String severity) {

    public static final String ERROR = "error";
    public static final String WARNING = "warning";

    public static ImportRowError error(String field, String code, String message) {
        return new ImportRowError(field, code, message, ERROR);
    }
}
