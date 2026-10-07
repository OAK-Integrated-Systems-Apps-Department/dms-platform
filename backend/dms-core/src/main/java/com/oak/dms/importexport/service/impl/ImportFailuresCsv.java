package com.oak.dms.importexport.service.impl;

import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.dto.ImportRowProblem;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes a bulk import's failure report as CSV: one line per problem, so a
 * file with two problems has two lines. A failed file with no readable
 * problems still gets a line (blank Field, Problem and Code) so it never
 * drops out of the report.
 * <p>
 * Quoting is RFC 4180-style, as in the audit log export. On top of that,
 * every cell starting with {@code = + - @}, tab or CR is prefixed with
 * {@code '} so a spreadsheet shows it as text instead of running it as a
 * formula - filenames and messages come from whoever uploaded the files.
 * The bytes are UTF-8 with a BOM, so Excel opens Sinhala and Tamil text
 * correctly.
 */
final class ImportFailuresCsv {

    static final List<String> HEADERS = List.of("Row", "File", "Field", "Problem", "Code");
    private static final String BOM = "﻿";

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
        return (BOM + text).getBytes(StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder csv, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(cell(cells[i]));
        }
        csv.append('\n');
    }

    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = startsLikeAFormula(value) ? "'" + value : value;
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static boolean startsLikeAFormula(String value) {
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }
}
