package com.oak.dms.importexport.service;

import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.dto.ImportTemplateFile;
import com.oak.dms.importexport.enums.TemplateFormat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
    default ImportTemplateFile generateTemplate(UUID documentTypeId) {
        return generateTemplate(documentTypeId, TemplateFormat.xlsx);
    }

    /** The template for the document type's current schema version, as .xlsx or .csv. */
    ImportTemplateFile generateTemplate(UUID documentTypeId, TemplateFormat format);

    /**
     * A saved template's file: its columns and headers over the document
     * type's current schema, marked with the template's id so its defaults
     * apply when the filled-in file is uploaded.
     */
    ImportTemplateFile generateFromSavedTemplate(UUID importTemplateId, TemplateFormat format);

    /** Creates a batch in {@code uploading} with one item per non-empty template row (an .xlsx template). */
    default BulkImportCreatedResponse createBatch(long templateSize, InputStream template) {
        return createBatch(null, templateSize, template);
    }

    /**
     * As {@link #createBatch(long, InputStream)}; {@code templateFilename}
     * picks the parser - {@code .csv} for a CSV template, anything else
     * (or null) for .xlsx.
     */
    BulkImportCreatedResponse createBatch(String templateFilename, long templateSize, InputStream template);

    /**
     * Checks a still-uploading batch's rows now - data types, mandatory
     * fields, the type's rules, duplicate filenames - the same checks the
     * worker runs after submit, minus "file not uploaded yet". Rows with
     * any problem (errors or warnings), in row order; nothing is changed.
     */
    List<ImportRowFailure> checkRows(UUID batchId);

    /**
     * A finished batch's failed rows as a template to fix and upload again:
     * the current schema's columns, each failed row's values as uploaded,
     * and a problems column (ignored on upload) saying why it failed.
     */
    ImportTemplateFile failedRowsTemplate(UUID batchId, TemplateFormat format);

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
     * The caller's own batches, newest first by default - finished ones
     * included, so a result can be reopened after the page was left.
     */
    Page<BulkImportStatusResponse> listMine(Pageable pageable);

    /** The caller's batch counting toward the active-batch limit (uploading, queued or running), if any. */
    Optional<BulkImportStatusResponse> getActive();

    /**
     * Moves an {@code uploading} or {@code queued} batch to {@code cancelled}
     * and deletes its staged files once the transaction commits.
     */
    BulkImportStatusResponse cancel(UUID batchId);
}
