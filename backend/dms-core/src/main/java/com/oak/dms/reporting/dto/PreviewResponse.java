package com.oak.dms.reporting.dto;

import java.util.List;

/**
 * What a preview shows. Totals count only rows the caller can see, and nothing here ever says how many were hidden:
 * {@code limitedToAccess} only says the result is narrowed.
 *
 * @param total           visible matching rows; null when the preview timed out
 * @param sample          at most 50 rows in the chosen sort order, one text cell per column
 * @param resolvedFilters the filters in words, relative dates shown as the absolute range they resolved to
 * @param timedOut        the preview was cancelled at the statement timeout - ask the user to narrow the filters
 * @param maxRows         the row limit for CSV/Excel, so the screen can warn before a run is refused
 * @param maxPdfRows      the row limit for PDF
 */
public record PreviewResponse(Long total, List<ColumnInfo> columns, List<List<String>> sample, boolean limitedToAccess,
                              List<String> resolvedFilters, boolean timedOut, String timeZone, int maxRows,
                              int maxPdfRows) {

    public record ColumnInfo(String key, String label, String type) {
    }
}
