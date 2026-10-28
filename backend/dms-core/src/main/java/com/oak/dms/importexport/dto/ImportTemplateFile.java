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

    /*
     * A record whose component is an array gets equals/hashCode from the
     * array's identity, so two records holding identical bytes compare
     * unequal and the same record hashes differently after a copy. Nothing
     * compares these today, which is exactly why it is worth fixing now -
     * the first caller to put one in a Set would get silently wrong answers
     * with no error to trace.
     */

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ImportTemplateFile file
                && java.util.Objects.equals(filename, file.filename)
                && java.util.Objects.equals(contentType, file.contentType)
                && java.util.Arrays.equals(content, file.content);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Objects.hash(filename, contentType) + java.util.Arrays.hashCode(content);
    }

    /** The size, never the bytes - this is a whole spreadsheet. */
    @Override
    public String toString() {
        return "ImportTemplateFile[filename=" + filename + ", contentType=" + contentType
                + ", content=" + (content == null ? 0 : content.length) + " bytes]";
    }
}
