package com.oak.dms.importexport.service;

import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.dto.ImportTemplateFile;

import java.io.InputStream;
import java.util.UUID;

/**
 * Bulk import intake (Module 2.1): template download, manifest upload,
 * per-file staging, and submit. Everything here is scoped to the caller's
 * tenant, and a batch is only visible to the user who created it. Field
 * validation and record creation happen later, in the import worker.
 */
public interface BulkImportService {

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
}
