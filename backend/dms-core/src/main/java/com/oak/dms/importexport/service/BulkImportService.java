package com.oak.dms.importexport.service;

import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.dto.ImportTemplateFile;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bulk import intake (Module 2.1): template download, manifest upload,
 * per-file staging, and submit. Everything here is scoped to the caller's
 * tenant, and a batch is only visible to the user who created it. Field
 * validation and record creation happen later, in the import worker.
 */
public interface BulkImportService {

    /** Active, non-deleted document types in the caller's tenant, in display order - the template picker's options. */
    List<ImportDocumentTypeOption> listDocumentTypes();

    /** An .xlsx template for the document type's current schema version. */
    ImportTemplateFile generateTemplate(UUID documentTypeId);

    /** Creates a batch in {@code uploading} with one item per non-empty template row. */
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

    /** The caller's batch counting toward the active-batch limit (uploading, queued or running), if any. */
    Optional<BulkImportStatusResponse> getActive();

    /**
     * Moves an {@code uploading} or {@code queued} batch to {@code cancelled}
     * and deletes its staged files once the transaction commits.
     */
    BulkImportStatusResponse cancel(UUID batchId);
}
