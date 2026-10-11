package com.oak.dms.importexport.export;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * What the xlsx "About this export" sheet says about the export.
 *
 * @param selection how the documents were chosen, as label/value lines
 *                  (the filters, or that they were picked one by one)
 */
public record ExportSummary(String requestedBy, OffsetDateTime requestedAt, OffsetDateTime generatedAt,
                            String format, List<Map.Entry<String, String>> selection, int requestedCount,
                            int exportedCount, int skippedCount) {
}
