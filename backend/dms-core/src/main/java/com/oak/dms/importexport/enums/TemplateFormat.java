package com.oak.dms.importexport.enums;

import com.oak.dms.importexport.exception.BulkImportException;

import java.util.Locale;

/** Import template file format (migration_batches.template_format). */
public enum TemplateFormat {
    xlsx,
    csv;

    /** Strict parse of a request parameter. */
    public static TemplateFormat fromValue(String value) {
        for (TemplateFormat format : values()) {
            if (format.name().equalsIgnoreCase(value)) {
                return format;
            }
        }
        throw BulkImportException.badRequest("INVALID_TEMPLATE_FORMAT", "format must be one of: xlsx, csv");
    }

    /** From an uploaded template's filename; null when it is neither. */
    public static TemplateFormat fromFilename(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx")) {
            return xlsx;
        }
        if (name.endsWith(".csv")) {
            return csv;
        }
        return null;
    }

    public String contentType() {
        return this == xlsx
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/csv";
    }
}
