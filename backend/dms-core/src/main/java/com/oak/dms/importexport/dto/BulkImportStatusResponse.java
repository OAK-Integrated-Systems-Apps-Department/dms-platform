package com.oak.dms.importexport.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @param documentTypeId   the batch's document type, so a screen can resume
 *                         an in-progress import without extra lookups
 * @param missingFilenames one entry per row still waiting for its file
 */
public record BulkImportStatusResponse(
        UUID batchId,
        UUID documentTypeId,
        String status,
        int totalRecords,
        int filesReceived,
        int successCount,
        int failureCount,
        List<String> missingFilenames,
        OffsetDateTime createdAt
) {
}
