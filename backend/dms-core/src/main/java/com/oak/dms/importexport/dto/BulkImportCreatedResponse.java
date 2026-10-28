package com.oak.dms.importexport.dto;

import java.util.List;
import java.util.UUID;

/**
 * @param expectedFilenames one entry per data row, in row order - a
 *                          filename listed on several rows appears once per
 *                          row, since each row needs its own upload
 */
public record BulkImportCreatedResponse(
        UUID batchId,
        int totalRecords,
        List<String> expectedFilenames
) {
}
