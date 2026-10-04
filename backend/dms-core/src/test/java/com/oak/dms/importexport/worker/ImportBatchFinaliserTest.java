package com.oak.dms.importexport.worker;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditActorType;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.helper.SystemActor;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.oak.dms.importexport.AuditTestSupport.mockAuditService;
import static com.oak.dms.importexport.AuditTestSupport.recorded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportBatchFinaliserTest {

    private final ImportBatchRepository importBatchRepository = mock(ImportBatchRepository.class);
    private final ImportItemRepository importItemRepository = mock(ImportItemRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final AuditService auditService = mockAuditService();

    private final ImportBatchFinaliser finaliser = new ImportBatchFinaliser(importBatchRepository,
            importItemRepository, auditService, transactionManager);

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void finalise_allRowsSucceeded_completes() {
        ImportBatch batch = runningBatch();
        stubCounts(batch, 3, 0, 0);

        assertThat(finaliser.finalise(batch.getImportBatchId())).contains(ImportBatchStatus.completed);

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.completed);
        assertThat(batch.getSuccessCount()).isEqualTo(3);
        assertThat(batch.getFailureCount()).isZero();
        assertThat(batch.getCompletedAt()).isNotNull();
        verify(importBatchRepository).save(batch);
    }

    @Test
    void finalise_someRowsFailed_completesWithErrors() {
        ImportBatch batch = runningBatch();
        stubCounts(batch, 2, 1, 0);

        assertThat(finaliser.finalise(batch.getImportBatchId())).contains(ImportBatchStatus.completed_with_errors);

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.completed_with_errors);
        assertThat(batch.getSuccessCount()).isEqualTo(2);
        assertThat(batch.getFailureCount()).isEqualTo(1);
        assertThat(batch.getCompletedAt()).isNotNull();
    }

    @Test
    void finalise_noRowSucceeded_fails() {
        ImportBatch batch = runningBatch();
        stubCounts(batch, 0, 3, 0);

        assertThat(finaliser.finalise(batch.getImportBatchId())).contains(ImportBatchStatus.failed);

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.failed);
        assertThat(batch.getSuccessCount()).isZero();
        assertThat(batch.getFailureCount()).isEqualTo(3);
        assertThat(batch.getCompletedAt()).isNotNull();
    }

    @Test
    void finalise_rowsSkippedAtPreview_countAsFailed() {
        ImportBatch batch = runningBatch();
        ImportItem created = ImportItem.builder().status(ImportItemStatus.success).build();
        ImportItem skipped = ImportItem.builder().status(ImportItemStatus.failed).skippedAtPreview(true).build();
        ImportItem failedLater = ImportItem.builder().status(ImportItemStatus.failed).build();
        List<ImportItem> items = List.of(created, skipped, failedLater);
        when(importBatchRepository.lockByImportBatchId(batch.getImportBatchId())).thenReturn(Optional.of(batch));
        when(importItemRepository.countByImportBatchIdAndStatus(eq(batch.getImportBatchId()), any()))
                .thenAnswer(invocation -> items.stream()
                        .filter(item -> item.getStatus() == invocation.getArgument(1))
                        .count());

        assertThat(finaliser.finalise(batch.getImportBatchId())).contains(ImportBatchStatus.completed_with_errors);

        assertThat(batch.getSuccessCount()).isEqualTo(1);
        assertThat(batch.getFailureCount()).isEqualTo(2);
    }

    @Test
    void finalise_onlyRowsSkippedAtPreviewFailed_stillCompletesWithErrors() {
        ImportBatch batch = runningBatch();
        // 2 created, 1 skipped at preview (failed) - nothing left pending.
        stubCounts(batch, 2, 1, 0);

        assertThat(finaliser.finalise(batch.getImportBatchId())).contains(ImportBatchStatus.completed_with_errors);
        assertThat(batch.getFailureCount()).isEqualTo(1);
    }

    @Test
    void finalise_rowsStillPending_onlyRefreshesCounts() {
        ImportBatch batch = runningBatch();
        stubCounts(batch, 1, 1, 1);

        assertThat(finaliser.finalise(batch.getImportBatchId())).isEmpty();

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.running);
        assertThat(batch.getCompletedAt()).isNull();
        assertThat(batch.getSuccessCount()).isEqualTo(1);
        assertThat(batch.getFailureCount()).isEqualTo(1);
        assertThat(recorded(auditService)).isEmpty();
    }

    @Test
    void finalise_batchNotRunning_isLeftAlone() {
        ImportBatch batch = runningBatch();
        batch.setStatus(ImportBatchStatus.completed);
        when(importBatchRepository.lockByImportBatchId(batch.getImportBatchId())).thenReturn(Optional.of(batch));

        assertThat(finaliser.finalise(batch.getImportBatchId())).isEmpty();

        verify(importBatchRepository, never()).save(any());
        verify(importItemRepository, never()).countByImportBatchIdAndStatus(any(), any());
        assertThat(recorded(auditService)).isEmpty();
    }

    @Test
    void finalise_allRowsSucceeded_recordsCompletedOnceAsSystemWithSuccess() {
        ImportBatch batch = runningBatch();
        stubCounts(batch, 3, 0, 0);

        finaliser.finalise(batch.getImportBatchId());

        AuditEventBuilder event = onlyCompletedEvent(batch);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.failureReason()).isNull();
        assertThat(event.newValue()).asInstanceOf(InstanceOfAssertFactories.MAP).containsExactlyInAnyOrderEntriesOf(
                Map.of("status", "completed", "successCount", 3L, "failureCount", 0L,
                        "initiatedBy", batch.getCreatedBy()));
    }

    @Test
    void finalise_someRowsFailed_recordsCompletedWithSuccessAndTheCounts() {
        ImportBatch batch = runningBatch();
        stubCounts(batch, 2, 1, 0);

        finaliser.finalise(batch.getImportBatchId());

        AuditEventBuilder event = onlyCompletedEvent(batch);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.newValue()).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("status", "completed_with_errors")
                .containsEntry("successCount", 2L)
                .containsEntry("failureCount", 1L);
    }

    @Test
    void finalise_noRowSucceeded_recordsCompletedWithFailure() {
        ImportBatch batch = runningBatch();
        stubCounts(batch, 0, 3, 0);

        finaliser.finalise(batch.getImportBatchId());

        AuditEventBuilder event = onlyCompletedEvent(batch);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.failureReason()).isEqualTo("No rows were imported");
        assertThat(event.newValue()).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("status", "failed")
                .containsEntry("failureCount", 3L);
    }

    @Test
    void finalise_calledAgainAfterFinalising_recordsNothingMore() {
        ImportBatch batch = runningBatch();
        stubCounts(batch, 3, 0, 0);

        finaliser.finalise(batch.getImportBatchId());
        // The worker and the completion job can both call it; the second
        // sees a batch that is no longer running.
        finaliser.finalise(batch.getImportBatchId());

        onlyCompletedEvent(batch);
    }

    @Test
    void finalise_creatorUnknown_stillRecordsCompleted() {
        ImportBatch batch = runningBatch();
        batch.setCreatedBy(null);
        stubCounts(batch, 1, 0, 0);

        assertThat(finaliser.finalise(batch.getImportBatchId())).contains(ImportBatchStatus.completed);

        assertThat(onlyCompletedEvent(batch).newValue()).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("initiatedBy", null);
    }

    @Test
    void finalise_keepsStagedFilesForResubmit() {
        ImportBatch batch = runningBatch();
        stubCounts(batch, 1, 1, 0);

        assertThat(finaliser.finalise(batch.getImportBatchId())).contains(ImportBatchStatus.completed_with_errors);

        // Rows that failed on their data keep their staged file - the purge
        // job removes it once the retention window has passed.
        verify(importItemRepository, never()).findAllByImportBatchIdAndStagedObjectKeyIsNotNull(any());
        verify(importItemRepository, never()).saveAll(any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    private AuditEventBuilder onlyCompletedEvent(ImportBatch batch) {
        List<AuditEventBuilder> events = recorded(auditService);
        assertThat(events).hasSize(1);
        AuditEventBuilder event = events.get(0);
        assertThat(event.action()).isEqualTo(AuditActionCatalog.BULK_IMPORT_COMPLETED);
        assertThat(event.actorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(event.actorId()).isEqualTo(SystemActor.SYSTEM_USER_ID);
        assertThat(event.tenantId()).isEqualTo(batch.getTenantId());
        assertThat(event.objectType()).isEqualTo(AuditObjectTypes.IMPORT_BATCH);
        assertThat(event.objectId()).isEqualTo(batch.getImportBatchId());
        return event;
    }

    private void stubCounts(ImportBatch batch, long succeeded, long failed, long pending) {
        UUID id = batch.getImportBatchId();
        when(importBatchRepository.lockByImportBatchId(id)).thenReturn(Optional.of(batch));
        when(importItemRepository.countByImportBatchIdAndStatus(id, ImportItemStatus.success)).thenReturn(succeeded);
        when(importItemRepository.countByImportBatchIdAndStatus(id, ImportItemStatus.failed)).thenReturn(failed);
        when(importItemRepository.countByImportBatchIdAndStatus(id, ImportItemStatus.pending)).thenReturn(pending);
    }

    private static ImportBatch runningBatch() {
        return ImportBatch.builder()
                .importBatchId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .sourceType(ImportBatch.SourceType.bulk_import)
                .documentTypeId(UUID.randomUUID())
                .schemaVersion(1)
                .status(ImportBatchStatus.running)
                .totalRecords(3)
                .correlationId(UUID.randomUUID())
                .createdBy(UUID.randomUUID())
                .build();
    }
}
