package com.oak.dms.ownership.helper;

/**
 * Minimal CSV writing for the history export. A cell starting with {@code = + - @} (or a tab/CR) is prefixed with
 * a single quote so a spreadsheet never runs it as a formula - titles and names are user-controlled.
 */
public final class OwnershipCsv {

    private OwnershipCsv() {
    }

    public static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        if (quote) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    public static String row(Object... cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(cell(cells[i]));
        }
        return line.append("\r\n").toString();
    }
}
