package com.oak.dms.importexport.validation;

/**
 * How an imported document's title is decided - shared by validation, record
 * creation and the failures report so they always agree.
 */
public final class ImportTitles {

    /** {@code documents.title} is VARCHAR(500) - see V32. */
    public static final int MAX_LENGTH = 500;

    private ImportTitles() {
    }

    /**
     * The file name without its extension ({@code "Q3 report.final.pdf"}
     * becomes {@code "Q3 report.final"}; a name that is only an extension,
     * like {@code ".env"}, is kept whole). Any folder path is dropped first.
     * Null if the name is blank.
     */
    public static String fromFilename(String filename) {
        String name = ImportFilenames.strip(filename);
        if (name.isEmpty()) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
