package com.oak.dms.common.sql;

import java.util.Locale;

/**
 * Turns what a person typed into a literal LIKE pattern.
 * <p>
 * In {@code LIKE}, {@code %} and {@code _} are wildcards and the backslash escapes them (PostgreSQL's
 * default). Text from a search box dropped straight into a pattern therefore means more than the
 * person typed: {@code %} matches everything, {@code _} any one character, and a handful of
 * {@code %_%_%_} makes a search that is slow for no reason. Not an injection - the value is always a
 * bound parameter - but a search for "50%" should find "50%", and nobody should be able to ask the
 * database for a pattern scan they didn't mean.
 */
public final class LikePatterns {

    private LikePatterns() {
    }

    /** {@code text} with LIKE's own characters made literal: {@code \}, {@code %} and {@code _}. */
    public static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** A lower-cased "contains" pattern for {@code lower(column) like :pattern}. */
    public static String contains(String text) {
        return "%" + escape(text.toLowerCase(Locale.ROOT)) + "%";
    }
}
