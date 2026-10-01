package com.oak.dms.importexport.validation;

/**
 * How an import row's document title is decided - shared by row validation
 * and record creation so both always agree.
 */
public final class ImportTitles {

    /** {@code documents.title} is VARCHAR(500) - see V32. */
    public static final int MAX_LENGTH = 500;

    private ImportTitles() {
    }

    /**
     * The row's title if one was entered, otherwise the file name without
     * its extension ({@code "Q3 report.final.pdf"} becomes
     * {@code "Q3 report.final"}; a name that is only an extension, like
     * {@code ".env"}, is kept whole). Null if both are blank.
     */
    public static String resolve(String title, String filename) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String name = filename.trim();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
