package com.oak.dms.importexport.enums;

import com.oak.dms.importexport.exception.InvalidExportJobValueException;

/**
 * What an export job produces (export_jobs.export_type). Constant names are
 * the stored values, lowercase like every other enum column in this schema
 * (see ck_export_jobs_export_type in V41, widened in V48).
 */
public enum ExportType {
    single_document,
    /** Several documents, chosen or matched by a filter (V48). */
    bulk_selection,
    bulk_folder,
    report_data;

    /**
     * Strict parse for values arriving from outside (request bodies, query
     * parameters): exact, case-sensitive match only, so an invalid value is
     * rejected here - with a clear 400 - before it could ever reach the
     * database CHECK constraint.
     */
    public static ExportType fromValue(String value) {
        for (ExportType type : values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw new InvalidExportJobValueException("export_type", value, values());
    }
}
