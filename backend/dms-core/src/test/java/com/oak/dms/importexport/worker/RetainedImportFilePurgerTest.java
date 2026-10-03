package com.oak.dms.importexport.worker;

import com.oak.dms.importexport.config.ImportExportProperties;
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
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetainedImportFilePurgerTest {

    private final ImportBatchRepository importBatchRepository = mock(ImportBatchRepository.class);
    private final ImportItemRepository importItemRepository = mock(ImportItemRepository.class);
    private final StorageLocationRepository storageLocationRepository = mock(StorageLocationRepository.class);
    private final ObjectStorageServiceFactory objectStorageServiceFactory = mock(ObjectStorageServiceFactory.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ImportExportProperties properties = new ImportExportProperties();
    private final StorageLocation location = StorageLocation.builder().storageLocationId(UUID.randomUUID()).build();

    private final RetainedImportFilePurger purger = new RetainedImportFilePurger(importBatchRepository,
            importItemRepository, new StagedImportFiles(storageLocationRepository, objectStorageServiceFactory),
            properties, transactionManager);

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(storageLocationRepository.findByStorageLocationIdAndDeletedFalse(location.getStorageLocationId()))
                .thenReturn(Optional.of(location));
        when(objectStorageServiceFactory.resolve(location)).thenReturn(storage);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void purge_queriesFinishedBatchesOlderThanRetentionWindow() {
        when(importBatchRepository.findBatchIdsWithExpiredStagedFiles(any(), any(), any())).thenReturn(List.of());

        purger.purgeExpiredFiles();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ImportBatchStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(importBatchRepository).findBatchIdsWithExpiredStagedFiles(statuses.capture(), cutoff.capture(),
                any(Pageable.class));
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(ImportBatchStatus.completed,
                ImportBatchStatus.completed_with_errors, ImportBatchStatus.failed);
        assertThat(cutoff.getValue()).isCloseTo(OffsetDateTime.now().minusHours(24), within(Duration.ofMinutes(1)));
    }

    @Test
    void purge_expiredFinishedBatch_clearsColumnsAndDeletesObjectsAfterCommit() {
        ImportBatch batch = finishedBatch(ImportBatchStatus.completed_with_errors, 30);
        ImportItem kept = stagedItem(batch, "imports/t/b/i1");
        stubCandidates(List.of(batch.getImportBatchId()));
        when(importBatchRepository.lockForRetainedFilePurge(batch.getImportBatchId())).thenReturn(Optional.of(batch));
        when(importItemRepository.findAllByImportBatchIdAndStagedObjectKeyIsNotNull(batch.getImportBatchId()))
                .thenReturn(List.of(kept));

        purger.purgeExpiredFiles();

        assertThat(kept.getStagedObjectKey()).isNull();
        assertThat(kept.getStagedStorageLocationId()).isNull();
        assertThat(kept.getStagedSizeBytes()).isNull();
        verify(importItemRepository).saveAll(List.of(kept));
        verify(storage, never()).delete(any(), anyString());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(storage).delete(location, "imports/t/b/i1");
    }

    @Test
    void purge_batchStillInsideRetentionWindowOnRecheck_isLeftAlone() {
        ImportBatch batch = finishedBatch(ImportBatchStatus.failed, 2);
        ImportItem kept = stagedItem(batch, "imports/t/b/i1");
        stubCandidates(List.of(batch.getImportBatchId()));
        when(importBatchRepository.lockForRetainedFilePurge(batch.getImportBatchId())).thenReturn(Optional.of(batch));
        when(importItemRepository.findAllByImportBatchIdAndStagedObjectKeyIsNotNull(batch.getImportBatchId()))
                .thenReturn(List.of(kept));

        purger.purgeExpiredFiles();

        assertThat(kept.getStagedObjectKey()).isEqualTo("imports/t/b/i1");
        verify(importItemRepository, never()).saveAll(any());
    }

    @Test
    void purge_batchNotFinishedOnRecheck_isLeftAlone() {
        ImportBatch batch = finishedBatch(ImportBatchStatus.running, 30);
        stubCandidates(List.of(batch.getImportBatchId()));
        when(importBatchRepository.lockForRetainedFilePurge(batch.getImportBatchId())).thenReturn(Optional.of(batch));

        purger.purgeExpiredFiles();

        verify(importItemRepository, never()).findAllByImportBatchIdAndStagedObjectKeyIsNotNull(any());
        verify(importItemRepository, never()).saveAll(any());
    }

    @Test
    void purge_batchLockedElsewhere_isSkipped() {
        UUID batchId = UUID.randomUUID();
        stubCandidates(List.of(batchId));
        when(importBatchRepository.lockForRetainedFilePurge(batchId)).thenReturn(Optional.empty());

        purger.purgeExpiredFiles();

        verify(importItemRepository, never()).findAllByImportBatchIdAndStagedObjectKeyIsNotNull(any());
        verify(importItemRepository, never()).saveAll(any());
    }

    @Test
    void purge_worksInChunksUntilAShortOne() {
        List<UUID> firstChunk = IntStream.range(0, RetainedImportFilePurger.CHUNK_SIZE)
                .mapToObj(i -> UUID.randomUUID()).toList();
        List<UUID> secondChunk = List.of(UUID.randomUUID());
        when(importBatchRepository.findBatchIdsWithExpiredStagedFiles(any(), any(), any()))
                .thenReturn(firstChunk, secondChunk);
        when(importBatchRepository.lockForRetainedFilePurge(any())).thenReturn(Optional.empty());

        purger.purgeExpiredFiles();

        verify(importBatchRepository, times(2)).findBatchIdsWithExpiredStagedFiles(any(), any(),
                eq(Pageable.ofSize(RetainedImportFilePurger.CHUNK_SIZE)));
        verify(importBatchRepository, times(RetainedImportFilePurger.CHUNK_SIZE + 1)).lockForRetainedFilePurge(any());
    }

    @Test
    void purge_fullChunkOfBatchesAlreadyTried_stopsInsteadOfLooping() {
        List<UUID> stuck = new ArrayList<>(IntStream.range(0, RetainedImportFilePurger.CHUNK_SIZE)
                .mapToObj(i -> UUID.randomUUID()).toList());
        when(importBatchRepository.findBatchIdsWithExpiredStagedFiles(any(), any(), any())).thenReturn(stuck);
        when(importBatchRepository.lockForRetainedFilePurge(any())).thenReturn(Optional.empty());

        purger.purgeExpiredFiles();

        verify(importBatchRepository, times(2)).findBatchIdsWithExpiredStagedFiles(any(), any(), any());
        verify(importBatchRepository, times(RetainedImportFilePurger.CHUNK_SIZE)).lockForRetainedFilePurge(any());
    }

    private void stubCandidates(List<UUID> batchIds) {
        when(importBatchRepository.findBatchIdsWithExpiredStagedFiles(any(), any(), any()))
                .thenReturn(batchIds, List.of());
    }

    private static ImportBatch finishedBatch(ImportBatchStatus status, int completedHoursAgo) {
        return ImportBatch.builder()
                .importBatchId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .sourceType(ImportBatch.SourceType.bulk_import)
                .documentTypeId(UUID.randomUUID())
                .schemaVersion(1)
                .status(status)
                .completedAt(OffsetDateTime.now().minusHours(completedHoursAgo))
                .correlationId(UUID.randomUUID())
                .createdBy(UUID.randomUUID())
                .build();
    }

    private ImportItem stagedItem(ImportBatch batch, String objectKey) {
        return ImportItem.builder()
                .importItemId(UUID.randomUUID())
                .importBatchId(batch.getImportBatchId())
                .status(ImportItemStatus.failed)
                .stagedObjectKey(objectKey)
                .stagedStorageLocationId(location.getStorageLocationId())
                .stagedSizeBytes(10L)
                .build();
    }
}
