package com.oak.dms.importexport.service.impl;

import com.oak.dms.importexport.csv.CsvCells;
import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.dto.ImportRowProblem;

import java.util.Arrays;
import java.util.List;

/**
 * Writes a bulk import's failure report as CSV: one line per problem, so a
 * file with two problems has two lines. A failed file with no readable
 * problems still gets a line (blank Field, Problem and Code) so it never
 * drops out of the report.
 * <p>
 * Cells are written by {@link CsvCells} - quoted RFC 4180-style, guarded
 * against formula injection, UTF-8 with a BOM - shared with bulk export.
 */
final class ImportFailuresCsv {

    static final List<String> HEADERS = List.of("Row", "File", "Field", "Problem", "Code");

    private ImportFailuresCsv() {
    }

    /** The report's text (no BOM) and its number of data lines. */
    record Written(String text, int rowCount) {
    }

    static Written write(List<ImportRowFailure> failures) {
        StringBuilder csv = new StringBuilder(String.join(",", HEADERS)).append('\n');
        int rowCount = 0;
        for (ImportRowFailure failure : failures) {
            String row = String.valueOf(failure.rowNumber());
            if (failure.problems().isEmpty()) {
                line(csv, row, failure.filename(), null, null, null);
                rowCount++;
                continue;
            }
            for (ImportRowProblem problem : failure.problems()) {
                line(csv, row, failure.filename(), problem.fieldLabel(), problem.message(), problem.code());
                rowCount++;
            }
        }
        return new Written(csv.toString(), rowCount);
    }

    /** {@code text} as UTF-8 bytes behind a BOM. */
    static byte[] withBom(String text) {
        return CsvCells.withBom(text);
    }

    private static void line(StringBuilder csv, String... cells) {
        CsvCells.line(csv, Arrays.asList(cells));
    }

    static String cell(String value) {
        return CsvCells.cell(value);
    }
}
