package com.oak.dms.reporting.export;

/**
 * CSV / Excel formula injection (OWASP): a text cell that a spreadsheet would read as a formula can run on the reader's machine
 * ({@code =HYPERLINK(...)}, {@code @SUM(...)}, DDE). Any text beginning with {@code =}, {@code +}, {@code -}, {@code @}, a tab or
 * a carriage return is therefore prefixed with a single quote, which every spreadsheet shows as nothing and treats as "this is
 * text". Applied to EVERY text cell and every text in a file's header block - a document title or a metadata value is
 * attacker-controlled data.
 * <p>
 * Numbers, dates and booleans are written natively and never pass through here: a negative number is a number, not a formula.
 */
public final class CellSanitizer {

    private CellSanitizer() {
    }

    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        char first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + text : text;
    }
}
