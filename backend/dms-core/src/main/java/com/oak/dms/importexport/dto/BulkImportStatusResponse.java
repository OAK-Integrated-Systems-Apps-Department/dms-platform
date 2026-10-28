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
     * @param skippedFilenames the first {@code skippedTotal} (capped - see
     *                  {@code BulkImportServiceImpl#OCR_SKIPPED_FILENAMES_LIMIT})
     *                  files the per-batch cap turned away, by name, in the
     *                  template's own row order. Named rather than counted so
     *                  the person can find them and run OCR on them
     *                  individually (POST /documents/{versionId}/ocr) - a bare
     *                  number tells them something was skipped without
     *                  telling them what. Capped rather than the full list: an
     *                  import of thousands of rows can skip thousands of
     *                  files, and this response is read by a browser, not a
     *                  paged screen of its own.
     * @param skippedTotal how many files were skipped in all - may be larger
     *                  than {@code skippedFilenames.size()}.
     */
    public record OcrProgress(int queued, int pending, int completed, int failed,
                              List<String> skippedFilenames, int skippedTotal) {
    }
}
