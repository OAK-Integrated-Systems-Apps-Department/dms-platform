package com.oak.dms.importexport.service;

import com.oak.dms.common.exception.ApiException;
import com.oak.dms.document.DocumentService;
import com.oak.dms.importexport.config.BulkImportProperties;
import com.oak.dms.importexport.entity.MigrationBatch;
import com.oak.dms.importexport.entity.MigrationItem;
import com.oak.dms.importexport.enums.MigrationItemStatus;
import com.oak.dms.importexport.repository.MigrationBatchRepository;
import com.oak.dms.importexport.repository.MigrationItemRepository;
import com.oak.dms.importexport.template.RowValueConverter;
import com.oak.dms.importexport.template.TemplateColumns;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.security.auth.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs a batch in the background: each pending row's file goes through the
 * normal malware-scanned upload (DocumentService#uploadDocument, as the
 * user who started the import), then its metadata is written. Rows are
 * independent - a failure is recorded on that row and the next one starts.
 * <p>
 * With ClamAV the scan finishes inside the upload call and the row is done
 * at once. When it doesn't (cloud scanner, engine briefly unavailable) the
 * row keeps its scan_result_id and {@link BulkImportReconciler} finishes it
 * once the scan resolves - {@link #settle} is shared by both.
 */
@Slf4j
@Component
public class BulkImportWorker {

    private final MigrationBatchRepository batchRepository;
    private final MigrationItemRepository itemRepository;
    private final BulkImportRowProcessor rowProcessor;
    private final BulkImportAuditor auditor;
    private final DocumentService documentService;
    private final DocumentTypeFieldService documentTypeFieldService;
    private final RowValueConverter rowValueConverter;
    private final ImportStagingArea stagingArea;
    private final BulkImportProperties properties;
    private final TaskExecutor taskExecutor;

    /** Batches running on this node - a batch is never run twice at once. */
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public BulkImportWorker(MigrationBatchRepository batchRepository,
                            MigrationItemRepository itemRepository,
                            BulkImportRowProcessor rowProcessor,
                            BulkImportAuditor auditor,
                            DocumentService documentService,
                            DocumentTypeFieldService documentTypeFieldService,
                            RowValueConverter rowValueConverter,
                            ImportStagingArea stagingArea,
                            BulkImportProperties properties,
                            @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.rowProcessor = rowProcessor;
        this.auditor = auditor;
        this.documentService = documentService;
        this.documentTypeFieldService = documentTypeFieldService;
        this.rowValueConverter = rowValueConverter;
        this.stagingArea = stagingArea;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
    }

    /** Queues the batch; returns at once. Call only after the batch's rows are committed. */
    public void start(UUID batchId, ImportActor actor) {
        if (!running.add(batchId)) {
            return;
        }
        try {
            taskExecutor.execute(() -> {
                try {
                    actor.bind();
                    run(batchId);
                } catch (RuntimeException e) {
                    log.error("Bulk import batch {} stopped unexpectedly", batchId, e);
                    rowProcessor.failBatch(batchId, "The import stopped unexpectedly. Try again.")
                            .ifPresent(auditor::finished);
                } finally {
                    TenantContext.clear();
                    running.remove(batchId);
                }
            });
        } catch (RuntimeException e) {
            running.remove(batchId);
            throw e;
        }
    }

    public boolean isRunning(UUID batchId) {
        return running.contains(batchId);
    }

    void run(UUID batchId) {
        MigrationBatch batch = batchRepository.findById(batchId).orElse(null);
        if (batch == null || batch.getStatus().isFinished()) {
            return;
        }
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(batch.getDocumentTypeId());
        if (schema.schemaVersion() != batch.getSchemaVersion()) {
            rowProcessor.failBatch(batchId, "The document type's fields changed after the import started. "
                    + "Download a new template and import the rows again.").ifPresent(auditor::finished);
            return;
        }
        List<SchemaField> fields = TemplateColumns.importableFields(schema);
        rowProcessor.markRunning(batchId);

        try (ImportFiles files = ImportFiles.open(stagingArea.fileDirs(batchId), properties.getMaxArchiveEntries())) {
            for (MigrationItem item : itemRepository
                    .findByMigrationBatchIdAndStatusAndScanResultIdIsNullOrderByRowNumberAsc(batchId,
                            MigrationItemStatus.pending)) {
                if (rowProcessor.cancelRequested(batchId)) {
                    rowProcessor.cancelUnstarted(batchId);
                    break;
                }
                importRow(batch, item, fields, files);
            }
        }
        rowProcessor.finishIfDone(batchId).ifPresent(auditor::finished);
    }

    private void importRow(MigrationBatch batch, MigrationItem item, List<SchemaField> fields, ImportFiles files) {
        Map<String, String> cells = item.getRowData();
        // Checked again, not trusted from submission time: a referenced
        // user may have been removed since.
        RowValueConverter.Converted converted = rowValueConverter.convert(fields, cells, batch.getTenantId());
        if (!converted.errors().isEmpty()) {
            rowProcessor.failRow(item.getMigrationItemId(), String.join("\n", converted.errors()));
            return;
        }
        if (!(files.resolve(item.getSourceReference()) instanceof ImportFiles.Found found)) {
            rowProcessor.failRow(item.getMigrationItemId(), TemplateColumns.FILENAME
                    + ": the file is no longer available - upload it again");
            return;
        }
        IngestionTicketResponse ticket;
        try (InputStream content = found.file().open()) {
            ticket = documentService.uploadDocument(batch.getDocumentTypeId(), BulkImportValidator.titleFor(cells),
                    found.file().name(), found.file().size(), content);
        } catch (ApiException e) {
            // Refused before a scan: too large, type not allowed, no engine.
            rowProcessor.failRow(item.getMigrationItemId(), "File: " + e.getMessage());
            return;
        } catch (IOException | RuntimeException e) {
            log.warn("Bulk import batch {} row {}: upload failed", batch.getMigrationBatchId(), item.getRowNumber(), e);
            rowProcessor.failRow(item.getMigrationItemId(), "File: could not be uploaded - try again");
            return;
        }
        settle(item.getMigrationItemId(), ticket.status(), ticket.scanResultId(), ticket.documentId(),
                ticket.threatName(), ticket.message(), valuesByFieldId(fields, converted.stored()));
    }

    /**
     * Moves a row on from its scan's outcome. Shared with the reconciler.
     *
     * @return true when the row is now settled (success or failed)
     */
    boolean settle(UUID itemId, ScanResultStatus status, UUID scanResultId, UUID documentId, String threatName,
                   String message, Map<String, Object> valuesByFieldId) {
        switch (status) {
            case clean, released_override -> {
                if (documentId == null) {
                    rowProcessor.markAwaitingScan(itemId, scanResultId);
                    return false;
                }
                try {
                    rowProcessor.completeRow(itemId, documentId, valuesByFieldId);
                } catch (RuntimeException e) {
                    // The document exists but its metadata couldn't be
                    // written: take the document back out so the row can
                    // simply be imported again.
                    log.error("Bulk import row {}: metadata for document {} could not be saved", itemId, documentId, e);
                    try {
                        documentService.deleteDocument(documentId);
                    } catch (RuntimeException deleteFailure) {
                        log.error("Bulk import row {}: could not remove document {}", itemId, documentId, deleteFailure);
                    }
                    rowProcessor.failRow(itemId, "Metadata could not be saved - import this row again");
                }
                return true;
            }
            case pending, infected -> {
                // infected: detected, not yet isolated - it ends quarantined.
                rowProcessor.markAwaitingScan(itemId, scanResultId);
                return false;
            }
            case quarantined -> rowProcessor.failRow(itemId, "File: blocked by the malware scan"
                    + (threatName != null ? " (" + threatName + ")" : "") + " and quarantined");
            case unscannable -> rowProcessor.failRow(itemId,
                    "File: encrypted or password-protected files can't be scanned");
            default -> rowProcessor.failRow(itemId, "File: " + (message != null ? message : "the malware scan failed"));
        }
        return true;
    }

    /** field_key -> value becomes field id -> value, as document_metadata stores it. */
    static Map<String, Object> valuesByFieldId(List<SchemaField> fields, Map<String, Object> byKey) {
        Map<String, Object> byId = new LinkedHashMap<>();
        for (SchemaField field : fields) {
            if (byKey.containsKey(field.fieldKey())) {
                byId.put(field.fieldId().toString(), byKey.get(field.fieldKey()));
            }
        }
        return byId;
    }
}
