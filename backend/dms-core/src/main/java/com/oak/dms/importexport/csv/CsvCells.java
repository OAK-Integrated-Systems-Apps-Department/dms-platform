package com.oak.dms.importexport.csv;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV cell writing shared by every CSV this module produces - the bulk
 * import failure report and bulk export's metadata.csv / skipped.csv.
 * <p>
 * Quoting is RFC 4180-style, as in the audit log export. On top of that,
 * every cell starting with {@code = + - @}, tab or CR is prefixed with
 * {@code '} so a spreadsheet shows it as text instead of running it as a
 * formula - filenames, titles and metadata come from users. The bytes are
 * UTF-8 with a BOM, so Excel opens Sinhala and Tamil text correctly.
 */
public final class CsvCells {

    public static final String BOM = "﻿";

    private CsvCells() {
    }

    /** {@code text} as UTF-8 bytes behind a BOM. */
    public static byte[] withBom(String text) {
        return (BOM + text).getBytes(StandardCharsets.UTF_8);
    }

    /** Appends one line - the cells comma-joined, each through {@link #cell}, then {@code \n}. */
    public static void line(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(cell(cells.get(i)));
        }
        csv.append('\n');
    }

    public static String cell(String value) {
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
