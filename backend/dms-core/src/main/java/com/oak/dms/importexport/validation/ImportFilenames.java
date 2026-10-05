package com.oak.dms.importexport.validation;

/**
 * Filenames as bulk import keeps and shows them: never with the folder path
 * a client may send along with an upload.
 */
public final class ImportFilenames {

    private ImportFilenames() {
    }

    /**
     * Drops any client-supplied directory prefix (either separator) and
     * surrounding whitespace; empty, never null, if nothing is left.
     */
    public static String strip(String filename) {
        if (filename == null) {
            return "";
        }
        int lastSeparator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        return filename.substring(lastSeparator + 1).trim();
    }
}
