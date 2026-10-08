package com.oak.dms.importexport.dto;

/** A generated template (or failed-rows report) ready to download. */
public record ImportTemplateFile(
        String filename,
        String contentType,
        byte[] content
) {

    public static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String CSV_CONTENT_TYPE = "text/csv";

    /** An .xlsx file. */
    public ImportTemplateFile(String filename, byte[] content) {
        this(filename, XLSX_CONTENT_TYPE, content);
    }
}
