package com.oak.dms.importexport.worker;

import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    private final ImportBatchFinaliser finaliser = new ImportBatchFinaliser(importBatchRepository,
            importItemRepository, transactionManager);

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
    }

    @Test
    void finalise_batchNotRunning_isLeftAlone() {
        ImportBatch batch = runningBatch();
        batch.setStatus(ImportBatchStatus.completed);
        when(importBatchRepository.lockByImportBatchId(batch.getImportBatchId())).thenReturn(Optional.of(batch));

        assertThat(finaliser.finalise(batch.getImportBatchId())).isEmpty();

        verify(importBatchRepository, never()).save(any());
        verify(importItemRepository, never()).countByImportBatchIdAndStatus(any(), any());
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
