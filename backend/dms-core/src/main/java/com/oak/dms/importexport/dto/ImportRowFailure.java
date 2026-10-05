package com.oak.dms.importexport.dto;

import java.util.List;

/**
 * A row of a bulk import that failed, with everything found wrong with it.
 *
 * @param filename the uploaded file's name, without any folder path
 * @param title    the document title taken from it (the name without its
 *                 extension)
 */
public record ImportRowFailure(
        int rowNumber,
        String filename,
        String title,
        List<ImportRowProblem> problems
) {
}
