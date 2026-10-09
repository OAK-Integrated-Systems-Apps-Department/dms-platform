package com.oak.dms.importexport.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @param documentTypeId   the batch's document type, so a screen can resume
 *                         an in-progress import without extra lookups
 * @param missingFilenames one entry per row still waiting for its file
 * @param ocr              OCR progress for a batch that asked for it, or null
 *                         when it did not - null and "0/0/0" mean different
 *                         things, and a screen must be able to tell "this
 *                         import has no OCR" from "its OCR has not started"
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
        OffsetDateTime createdAt,
        OcrProgress ocr
) {

    /**
     * "OCR pending / done / failed" for one import batch.
     *
     * <p>{@code queued} is how many of the batch's files actually got an OCR
     * slot - it is not the same as {@code totalRecords}, because the per-batch
     * cap (dms.ocr.max-per-import-batch) and the tenant queue limit both stop
     * at a ceiling. Showing progress against totalRecords would leave a 5,000
     * file import permanently stuck at "500 of 5,000" with nothing wrong.
     *
     * @param queued    files from this batch that claimed an OCR slot
     * @param pending   queued and not finished yet (includes work the backlog
     *                  sweeper is still holding)
     * @param completed OCR finished and its fields applied
     * @param failed    OCR ran and could not produce a result
     */
    public record OcrProgress(int queued, int pending, int completed, int failed) {
    }
}
