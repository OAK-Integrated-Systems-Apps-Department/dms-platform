package com.oak.dms.importexport.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @param documentTypeId    the batch's document type, so a screen can resume
 *                          an in-progress import without extra lookups
 * @param totalRecords      every upload into the batch, accepted or rejected
 * @param filesReceived     uploads that were accepted
 * @param filesRejected     uploads that were refused - each is a failed item
 * @param rejectedFilenames the refused uploads' names, in upload order
 * @param acceptedFiles     the accepted uploads, in row order - only while
 *                          the batch is {@code uploading}; empty otherwise
 * @param cancelReason      {@code user} or {@code expired} for a cancelled
 *                          batch; null otherwise - and null on a cancelled
 *                          batch means its user cancelled it
 */
public record BulkImportStatusResponse(
        UUID batchId,
        UUID documentTypeId,
        String status,
        int totalRecords,
        int filesReceived,
        int filesRejected,
        int successCount,
        int failureCount,
        List<String> rejectedFilenames,
        List<ImportAcceptedFile> acceptedFiles,
        OffsetDateTime createdAt,
        String cancelReason
) {
}
