package com.oak.dms.importexport.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the caller's "Recent imports" list.
 *
 * @param documentTypeName the type's name, kept for inactive or deleted types
 *                         too (this is history); null if it can't be found
 * @param completedAt      when the worker finished the batch; null until then
 *                         (and for a cancelled batch)
 * @param cancelReason     {@code user} or {@code expired} for a cancelled
 *                         batch; null otherwise - and null on a cancelled
 *                         batch means its user cancelled it
 */
public record BulkImportSummary(
        UUID batchId,
        UUID documentTypeId,
        String documentTypeName,
        String status,
        int totalRecords,
        int successCount,
        int failureCount,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        String cancelReason
) {
}
