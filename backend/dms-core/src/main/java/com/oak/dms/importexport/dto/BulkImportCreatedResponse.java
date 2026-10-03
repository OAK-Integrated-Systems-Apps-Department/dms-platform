package com.oak.dms.importexport.dto;

import java.util.List;
import java.util.UUID;

/**
 * @param totalRecords      every data row of the template, skipped ones included
 * @param expectedFilenames one entry per row that needs a file, in row order -
 *                          a filename listed on several rows appears once per
 *                          row, since each row needs its own upload. Rows
 *                          skipped for template errors, and rows that reused
 *                          a kept file, are left out.
 * @param skippedRows       rows saved as failed because of template errors;
 *                          they need no file and show up in the results
 * @param reusedFiles       rows of a resubmit that took over the file kept
 *                          from the original import - already received
 */
public record BulkImportCreatedResponse(
        UUID batchId,
        int totalRecords,
        List<String> expectedFilenames,
        int skippedRows,
        int reusedFiles
) {
}
