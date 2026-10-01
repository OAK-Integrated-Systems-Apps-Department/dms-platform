package com.oak.dms.importexport.dto;

import com.oak.dms.importexport.enums.MigrationBatchStatus;
import com.oak.dms.importexport.enums.TemplateFormat;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A bulk import as its creator sees it. {@code pendingCount} is rows not
 * settled yet (still to import, or still being scanned); rows cancelled
 * with the batch are counted in none of the three counts.
 */
public record MigrationBatchResponse(
        UUID batchId,
        UUID documentTypeId,
        String documentTypeName,
        int schemaVersion,
        TemplateFormat templateFormat,
        MigrationBatchStatus status,
        int totalRecords,
        int successCount,
        int failureCount,
        long pendingCount,
        boolean cancelRequested,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
) {
}
