package com.oak.dms.importexport.service;

import com.oak.dms.importexport.config.BulkImportProperties;
import com.oak.dms.importexport.entity.MigrationBatch;
import com.oak.dms.importexport.entity.MigrationItem;
import com.oak.dms.importexport.entity.UploadSession;
import com.oak.dms.importexport.enums.MigrationBatchStatus;
import com.oak.dms.importexport.repository.MigrationBatchRepository;
import com.oak.dms.importexport.repository.MigrationItemRepository;
import com.oak.dms.importexport.repository.UploadSessionRepository;
import com.oak.dms.importexport.template.RowValueConverter;
import com.oak.dms.importexport.template.TemplateColumns;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.repository.ScanResultRepository;
import com.oak.dms.scanning.service.DocumentPromoter;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The background half of bulk import:
 * <ul>
 *   <li>finishes rows whose malware scan was still running when the worker
 *   moved on - there is no "document promoted" event to listen for, so
 *   their scan_results rows are polled;</li>
 *   <li>on startup, restarts batches a restart interrupted. A row that was
 *   mid-upload at that moment may be imported twice - it's the one case
 *   the scan gate gives no way to detect;</li>
 *   <li>deletes staged files of finished batches after
 *   {@code dms.import.staging-retention}, and expired upload sessions.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BulkImportReconciler {

    private static final int PAGE = 200;
    private static final Set<MigrationBatchStatus> UNFINISHED =
            EnumSet.of(MigrationBatchStatus.pending, MigrationBatchStatus.running);
    private static final Set<MigrationBatchStatus> FINISHED = EnumSet.of(MigrationBatchStatus.completed,
            MigrationBatchStatus.completed_with_errors, MigrationBatchStatus.failed, MigrationBatchStatus.cancelled);

    private final MigrationBatchRepository batchRepository;
    private final MigrationItemRepository itemRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final ScanResultRepository scanResultRepository;
    private final DocumentPromoter documentPromoter;
    private final DocumentTypeFieldService documentTypeFieldService;
    private final RowValueConverter rowValueConverter;
    private final BulkImportRowProcessor rowProcessor;
    private final BulkImportAuditor auditor;
    private final BulkImportWorker worker;
    private final ImportStagingArea stagingArea;
    private final BulkImportProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterrupted() {
        try {
            for (MigrationBatch batch : batchRepository.findByStatusIn(UNFINISHED)) {
                if (!batch.isDeleted()) {
                    log.info("Resuming bulk import batch {}", batch.getMigrationBatchId());
                    worker.start(batch.getMigrationBatchId(), ImportActor.of(batch.getTenantId(), batch.getCreatedBy()));
                }
            }
        } catch (RuntimeException e) {
            log.error("Could not resume interrupted bulk imports", e);
        }
    }

    @Scheduled(fixedDelayString = "${dms.import.reconcile-interval:PT15S}", initialDelayString = "PT30S")
    public void reconcileAwaitingScans() {
        List<MigrationItem> awaiting = itemRepository.findAwaitingScan(PageRequest.of(0, PAGE));
        if (awaiting.isEmpty()) {
            return;
        }
        Map<UUID, ScanResult> scans = new HashMap<>();
        scanResultRepository.findByScanResultIdIn(awaiting.stream().map(MigrationItem::getScanResultId).toList())
                .forEach(s -> scans.put(s.getScanResultId(), s));
        Map<UUID, MigrationBatch> batches = new HashMap<>();
        Map<UUID, List<SchemaField>> fieldsByBatch = new HashMap<>();
        Set<UUID> touched = new HashSet<>();

        for (MigrationItem item : awaiting) {
            ScanResult scan = scans.get(item.getScanResultId());
            if (scan == null || !scan.getStatus().isTerminal()) {
                continue;
            }
            MigrationBatch batch = batches.computeIfAbsent(item.getMigrationBatchId(),
                    id -> batchRepository.findById(id).orElse(null));
            if (batch == null) {
                continue;
            }
            try {
                ImportActor.of(batch.getTenantId(), batch.getCreatedBy()).bind();
                List<SchemaField> fields = fieldsByBatch.computeIfAbsent(batch.getMigrationBatchId(),
                        id -> fieldsAtBatchVersion(batch));
                settle(item, scan, batch, fields);
                touched.add(batch.getMigrationBatchId());
            } catch (RuntimeException e) {
                log.error("Bulk import row {} could not be reconciled", item.getMigrationItemId(), e);
            } finally {
                TenantContext.clear();
            }
        }
        for (UUID batchId : touched) {
            if (!worker.isRunning(batchId)) {
                rowProcessor.finishIfDone(batchId).ifPresent(auditor::finished);
            }
        }
    }

    private void settle(MigrationItem item, ScanResult scan, MigrationBatch batch, List<SchemaField> fields) {
        if (fields == null) {
            rowProcessor.failRow(item.getMigrationItemId(), "The document type's fields changed while the file was "
                    + "being scanned - import this row again with a new template");
            return;
        }
        UUID documentId = scan.getDocumentVersionId() == null
                ? null
                : documentPromoter.findDocumentIdForVersion(batch.getTenantId(), scan.getDocumentVersionId()).orElse(null);
        RowValueConverter.Converted converted = rowValueConverter.convert(fields, item.getRowData(), batch.getTenantId());
        if (!converted.errors().isEmpty() && documentId != null) {
            // Rare: a referenced user was removed while the file was scanned.
            rowProcessor.failRow(item.getMigrationItemId(), String.join("\n", converted.errors()));
            return;
        }
        worker.settle(item.getMigrationItemId(), scan.getStatus(), scan.getScanResultId(), documentId,
                scan.getThreatName(), scan.getFailureReason(),
                BulkImportWorker.valuesByFieldId(fields, converted.stored()));
    }

    /** null when the schema has moved on from the batch's version. */
    private List<SchemaField> fieldsAtBatchVersion(MigrationBatch batch) {
        DocumentTypeSchemaResponse schema = documentTypeFieldService.getSchema(batch.getDocumentTypeId());
        return schema.schemaVersion() == batch.getSchemaVersion() ? TemplateColumns.importableFields(schema) : null;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void cleanUp() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(properties.getStagingRetention());
        for (MigrationBatch batch : batchRepository.findByStatusInAndCompletedAtBefore(FINISHED, cutoff)) {
            Path dir = stagingArea.batchDir(batch.getMigrationBatchId());
            if (Files.exists(dir)) {
                stagingArea.deleteQuietly(dir);
            }
        }
        stagingArea.deleteAbandonedIncoming(Duration.ofHours(6));
        expireSessions();
    }

    private void expireSessions() {
        for (UploadSession session : uploadSessionRepository.findByDeletedFalseAndExpiresAtBefore(OffsetDateTime.now())) {
            stagingArea.deleteQuietly(stagingArea.sessionFile(session.getUploadSessionId()));
            session.setDeleted(true);
            session.setDeletedAt(OffsetDateTime.now());
            uploadSessionRepository.save(session);
        }
    }
}
