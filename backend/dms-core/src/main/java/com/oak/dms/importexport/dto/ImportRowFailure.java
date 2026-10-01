package com.oak.dms.importexport.dto;

import java.util.List;

/**
 * A row of a bulk import that failed, with everything found wrong with it.
 *
 * @param filename the row's filename cell, as uploaded
 * @param title    the row's raw title cell; null if it was left blank
 */
public record ImportRowFailure(
        int rowNumber,
        String filename,
        String title,
        List<ImportRowProblem> problems
) {
}
