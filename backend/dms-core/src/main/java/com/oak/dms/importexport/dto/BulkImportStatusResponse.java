package com.oak.dms.importexport.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @param documentTypeId   the batch's document type, so a screen can resume
 *                         an in-progress import without extra lookups
 * @param filesReceived    rows that need a file and have one - rows skipped
 *                         for template errors are never counted
 * @param missingFilenames one entry per row still waiting for its file;
 *                         skipped rows never wait for one
 * @param skippedRows      rows skipped at creation because of template errors
 * @param cancelReason     {@code user} or {@code expired} for a cancelled
 *                         batch; null otherwise - and null on a cancelled
 *                         batch means its user cancelled it
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
        int skippedRows,
        OffsetDateTime createdAt,
        String cancelReason
) {
}
