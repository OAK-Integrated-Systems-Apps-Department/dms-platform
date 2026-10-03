package com.oak.dms.importexport.service;

import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.BulkImportSummary;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.dto.ImportTemplateFile;
import com.oak.dms.importexport.dto.ImportTemplatePreview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bulk import intake (Module 2.1): template download, manifest upload,
 * per-file staging, and submit. Everything here is scoped to the caller's
 * tenant, and a batch is only visible to the user who created it. Rows are
 * checked (all but their file) when the template is uploaded; full
 * validation and record creation happen later, in the import worker.
 */
public interface BulkImportService {

    /** Active, non-deleted document types in the caller's tenant, in display order - the template picker's options. */
    List<ImportDocumentTypeOption> listDocumentTypes();

    /** An .xlsx template for the document type's current schema version. */
    ImportTemplateFile generateTemplate(UUID documentTypeId);

    /**
     * The caller's {@code completed_with_errors} or {@code failed} batch's
     * failed rows as a fixable template: the document type's current
     * template with one pre-filled row per failed item, an "Import errors"
     * column, and the batch recorded so an upload of it is a resubmit.
     * Also the exportable failure report, so an inactive type doesn't block
     * it.
     */
    ImportTemplateFile generateFailedRowsTemplate(UUID batchId);

    /**
     * Checks a filled-in template exactly as {@link #createBatch} would -
     * the same template-level rejections, and every row check except the
     * file-present one - and reports the rows with errors. Writes nothing,
     * and doesn't check the active-batch limit.
     */
    ImportTemplatePreview previewTemplate(long templateSize, InputStream template);

    /**
     * Creates a batch in {@code uploading} with one item per non-empty
     * template row. The rows are re-checked as {@link #previewTemplate}
     * does: rows with errors are saved {@code failed} (skipped at preview)
     * and need no file. If no row passes, nothing is saved and the template
     * is rejected.
     */
    BulkImportCreatedResponse createBatch(long templateSize, InputStream template);

    /** Stages one file against the lowest-numbered unfilled row that lists its filename. */
    ImportFileReceivedResponse receiveFile(UUID batchId, String originalFilename, String contentType,
                                           long contentLength, InputStream content);

    /** Moves the batch to {@code queued} and publishes {@code import_batch.submitted}. */
    void submit(UUID batchId);

    BulkImportStatusResponse getStatus(UUID batchId);

    /**
     * The batch's failed rows in row order, each with its problems and a
     * display label for the column each problem is about (resolved against
     * the batch's own schema version).
     */
    List<ImportRowFailure> getFailures(UUID batchId);

    /**
     * The caller's own batches in their tenant, in every status (cancelled and
     * failed included), newest first. The page number and size come from
     * {@code pageable}, with the size capped at 50. Any sort it asks for is ignored.
     */
    Page<BulkImportSummary> listBatches(Pageable pageable);

    /** The caller's batch counting toward the active-batch limit (uploading, queued or running), if any. */
    Optional<BulkImportStatusResponse> getActive();

    /**
     * Moves an {@code uploading} or {@code queued} batch to {@code cancelled}
     * and deletes its staged files once the transaction commits.
     */
    BulkImportStatusResponse cancel(UUID batchId);
}
