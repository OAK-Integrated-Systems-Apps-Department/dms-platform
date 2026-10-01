package com.oak.dms.importexport.worker;

import com.oak.dms.importexport.service.BulkImportAuditor;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportBatchFinaliserTest {

    private final ImportBatchRepository importBatchRepository = mock(ImportBatchRepository.class);
    private final ImportItemRepository importItemRepository = mock(ImportItemRepository.class);
    private final StorageLocationRepository storageLocationRepository = mock(StorageLocationRepository.class);
    private final ObjectStorageServiceFactory objectStorageServiceFactory = mock(ObjectStorageServiceFactory.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private final ImportBatchFinaliser finaliser = new ImportBatchFinaliser(importBatchRepository,
            importItemRepository, new StagedImportFiles(storageLocationRepository, objectStorageServiceFactory),
            mock(BulkImportAuditor.class), transactionManager);

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
    void finalise_rowsStillPending_onlyRefreshesCounts() {
        ImportBatch batch = runningBatch();
        stubCounts(batch, 1, 1, 1);

        assertThat(finaliser.finalise(batch.getImportBatchId())).isEmpty();

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.running);
        assertThat(batch.getCompletedAt()).isNull();
        assertThat(batch.getSuccessCount()).isEqualTo(1);
        assertThat(batch.getFailureCount()).isEqualTo(1);
        verify(importItemRepository, never()).findAllByImportBatchIdAndStagedObjectKeyIsNotNull(any());
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
    void finalise_deletesLeftoverStagedFilesAfterCommit() {
        ImportBatch batch = runningBatch();
        stubCounts(batch, 1, 1, 0);
        StorageLocation location = StorageLocation.builder().storageLocationId(UUID.randomUUID()).build();
        ImportItem leftover = ImportItem.builder()
                .importItemId(UUID.randomUUID())
                .importBatchId(batch.getImportBatchId())
                .status(ImportItemStatus.failed)
                .stagedObjectKey("imports/key-9")
                .stagedStorageLocationId(location.getStorageLocationId())
                .stagedSizeBytes(10L)
                .build();
        when(importItemRepository.findAllByImportBatchIdAndStagedObjectKeyIsNotNull(batch.getImportBatchId()))
                .thenReturn(List.of(leftover));
        when(storageLocationRepository.findByStorageLocationIdAndDeletedFalse(location.getStorageLocationId()))
                .thenReturn(Optional.of(location));
        when(objectStorageServiceFactory.resolve(location)).thenReturn(storage);

        finaliser.finalise(batch.getImportBatchId());

        assertThat(leftover.getStagedObjectKey()).isNull();
        assertThat(leftover.getStagedStorageLocationId()).isNull();
        assertThat(leftover.getStagedSizeBytes()).isNull();
        verify(importItemRepository).saveAll(List.of(leftover));
        verify(storage, never()).delete(any(), anyString());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(storage).delete(location, "imports/key-9");
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
