package com.oak.dms.importexport.service;

import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.BulkImportSummary;
import com.oak.dms.importexport.dto.ImportDefaultsResponse;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.dto.ImportFailuresCsvFile;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.dto.ImportRowFailure;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bulk import intake (Module 2.1), files first: open a batch for a document
 * type, upload files one at a time (each becomes one item, and later one
 * document), then submit. Nobody enters metadata - every document gets the
 * type's default values (see {@code ImportDefaults}). Everything here is
 * scoped to the caller's tenant, and a batch is only visible to the user who
 * created it. Validation and record creation happen later, in the import
 * worker.
 */
public interface BulkImportService {

    /** Active, non-deleted document types in the caller's tenant, in display order - the import picker's options. */
    List<ImportDocumentTypeOption> listDocumentTypes();

    /**
     * The default values an import into the document type would apply now,
     * at its current schema version, and its mandatory fields that have no
     * usable default. Read-only.
     */
    ImportDefaultsResponse getDefaults(UUID documentTypeId);

    /**
     * Creates an empty batch in {@code uploading} at the document type's
     * current schema version. Refused for an inactive type, over the
     * active-batch limit, and - with {@code enforce-mandatory-fields} on -
     * for a type with mandatory fields that have no usable default.
     */
    BulkImportCreatedResponse createBatch(UUID documentTypeId);

    /**
     * Adds one file to the batch as a new item. An accepted file is staged;
     * a refused one (type, empty, password-protected, batch size) is saved
     * as a failed item with FILE_REJECTED and its exception still thrown.
     * <p>
     * {@code clientFileId} (optional; 1-64 letters, digits, '-' or '_') is the
     * browser's ID for the upload. If an item in the batch already has it,
     * this is a retry: that item's original outcome is returned - the same
     * response, or the same rejection thrown again - and nothing is created
     * or staged.
     */
    ImportFileReceivedResponse receiveFile(UUID batchId, String clientFileId, String originalFilename,
                                           String contentType, long contentLength, InputStream content);

    /**
     * Moves the batch to {@code queued} and publishes {@code import_batch.submitted};
     * refused unless at least one file was accepted.
     */
    void submit(UUID batchId);

    BulkImportStatusResponse getStatus(UUID batchId);

    /**
     * The batch's failed items in upload order, each with its errors (not
     * warnings - a failed file never became a document) and a
     * display label for the field each problem is about (resolved against
     * the batch's own schema version).
     */
    List<ImportRowFailure> getFailures(UUID batchId);

    /**
     * {@link #getFailures} as a CSV file - one line per problem (Row, File,
     * Field, Problem, Code), UTF-8 with a BOM - and records
     * BULK_IMPORT_FAILED_ROWS_DOWNLOADED. Same scoping as getFailures.
     */
    ImportFailuresCsvFile exportFailuresCsv(UUID batchId);

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
