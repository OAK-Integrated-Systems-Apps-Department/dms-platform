package com.oak.dms.importexport.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.importexport.validation.ImportRowError;
import com.oak.dms.outbox.OutboxEventPublisher;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportBatchRecoveryJobTest {

    private static final List<String> UPLOADING = List.of("uploading");
    private static final List<String> STUCK = List.of("queued", "running");

    private final ImportBatchRepository importBatchRepository = mock(ImportBatchRepository.class);
    private final ImportItemRepository importItemRepository = mock(ImportItemRepository.class);
    private final StorageLocationRepository storageLocationRepository = mock(StorageLocationRepository.class);
    private final ObjectStorageServiceFactory objectStorageServiceFactory = mock(ObjectStorageServiceFactory.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final OutboxEventPublisher outboxEventPublisher = mock(OutboxEventPublisher.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ImportExportProperties properties = new ImportExportProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StorageLocation location = StorageLocation.builder().storageLocationId(UUID.randomUUID()).build();

    private final ImportBatchRecoveryJob job = new ImportBatchRecoveryJob(importBatchRepository, importItemRepository,
            new StagedImportFiles(storageLocationRepository, objectStorageServiceFactory), outboxEventPublisher,
            new ImportBatchFinaliser(importBatchRepository, importItemRepository, transactionManager), properties,
            objectMapper, transactionManager);

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(storageLocationRepository.findByStorageLocationIdAndDeletedFalse(location.getStorageLocationId()))
                .thenReturn(Optional.of(location));
        when(objectStorageServiceFactory.resolve(location)).thenReturn(storage);
        when(importBatchRepository.findInactiveBatchIds(any(), any(), anyInt())).thenReturn(List.of());
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // --- candidates and config ---

    @Test
    void recover_queriesUploadingAndStuckBatchesWithDefaultWindows() {
        job.recoverBatches();

        verify(importBatchRepository).findInactiveBatchIds(eq(UPLOADING), cutoffAround(24), eq(50));
        verify(importBatchRepository).findInactiveBatchIds(eq(STUCK), cutoffAround(2), eq(50));
    }

    @Test
    void recover_usesConfiguredWindows() {
        properties.setAbandonedUploadHours(5);
        properties.setStuckBatchHours(1);

        job.recoverBatches();

        verify(importBatchRepository).findInactiveBatchIds(eq(UPLOADING), cutoffAround(5), anyInt());
        verify(importBatchRepository).findInactiveBatchIds(eq(STUCK), cutoffAround(1), anyInt());
    }

    @Test
    void recover_worksInChunksUntilAShortOne() {
        List<UUID> firstChunk = IntStream.range(0, ImportBatchRecoveryJob.CHUNK_SIZE)
                .mapToObj(i -> UUID.randomUUID()).toList();
        when(importBatchRepository.findInactiveBatchIds(eq(UPLOADING), any(), anyInt()))
                .thenReturn(firstChunk, List.of(UUID.randomUUID()));
        when(importBatchRepository.lockForRecovery(any())).thenReturn(Optional.empty());

        job.recoverBatches();

        verify(importBatchRepository, times(2)).findInactiveBatchIds(eq(UPLOADING), any(), anyInt());
        verify(importBatchRepository, times(ImportBatchRecoveryJob.CHUNK_SIZE + 1)).lockForRecovery(any());
    }

    @Test
    void recover_fullChunkOfBatchesAlreadyTried_stopsInsteadOfLooping() {
        List<UUID> stuck = new ArrayList<>(IntStream.range(0, ImportBatchRecoveryJob.CHUNK_SIZE)
                .mapToObj(i -> UUID.randomUUID()).toList());
        when(importBatchRepository.findInactiveBatchIds(eq(STUCK), any(), anyInt())).thenReturn(stuck);
        when(importBatchRepository.lockForRecovery(any())).thenReturn(Optional.empty());

        job.recoverBatches();

        verify(importBatchRepository, times(2)).findInactiveBatchIds(eq(STUCK), any(), anyInt());
        verify(importBatchRepository, times(ImportBatchRecoveryJob.CHUNK_SIZE)).lockForRecovery(any());
    }

    @Test
    void recover_lockedBatch_isSkipped() {
        UUID batchId = UUID.randomUUID();
        when(importBatchRepository.findInactiveBatchIds(eq(UPLOADING), any(), anyInt()))
                .thenReturn(List.of(batchId), List.of());
        when(importBatchRepository.lockForRecovery(batchId)).thenReturn(Optional.empty());

        job.recoverBatches();

        verify(importBatchRepository, never()).isInactiveSince(any(), any());
        verify(importBatchRepository, never()).save(any());
        verify(importItemRepository, never()).saveAll(any());
    }

    @Test
    void recover_oneBatchFailing_doesNotStopTheRest() {
        ImportBatch first = batch(ImportBatchStatus.uploading, 0);
        ImportBatch second = batch(ImportBatchStatus.uploading, 0);
        when(importBatchRepository.findInactiveBatchIds(eq(UPLOADING), any(), anyInt()))
                .thenReturn(List.of(first.getImportBatchId(), second.getImportBatchId()), List.of());
        when(importBatchRepository.lockForRecovery(first.getImportBatchId()))
                .thenThrow(new IllegalStateException("db hiccup"));
        stubLocked(second, true);
        when(importItemRepository.findAllByImportBatchIdAndStagedObjectKeyIsNotNull(any())).thenReturn(List.of());

        job.recoverBatches();

        assertThat(second.getStatus()).isEqualTo(ImportBatchStatus.cancelled);
    }

    // --- abandoned uploads ---

    @Test
    void abandoned_cancelsAsExpiredAndDeletesStagedFilesAfterCommit() {
        ImportBatch batch = batch(ImportBatchStatus.uploading, 0);
        ImportItem staged = item(batch, ImportItemStatus.pending, "imports/t/b/i1", null, null);
        stubLocked(batch, true);
        when(importItemRepository.findAllByImportBatchIdAndStagedObjectKeyIsNotNull(batch.getImportBatchId()))
                .thenReturn(List.of(staged));

        assertThat(job.expireAbandoned(batch.getImportBatchId(), OffsetDateTime.now().minusHours(24))).isTrue();

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.cancelled);
        assertThat(batch.getCancelReason()).isEqualTo(ImportBatch.CancelReason.expired);
        assertThat(batch.getUpdatedAt()).isCloseTo(OffsetDateTime.now(), within(Duration.ofMinutes(1)));
        verify(importBatchRepository).save(batch);
        assertThat(staged.getStagedObjectKey()).isNull();
        assertThat(staged.getStagedStorageLocationId()).isNull();
        verify(importItemRepository).saveAll(List.of(staged));
        verify(storage, never()).delete(any(), anyString());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(storage).delete(location, "imports/t/b/i1");
    }

    @Test
    void abandoned_recentActivityOnRecheck_keepsItAlive() {
        // isInactiveSince covers activity on the batch and on any of its items.
        ImportBatch batch = batch(ImportBatchStatus.uploading, 0);
        stubLocked(batch, false);
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(24);

        assertThat(job.expireAbandoned(batch.getImportBatchId(), cutoff)).isFalse();

        verify(importBatchRepository).isInactiveSince(batch.getImportBatchId(), cutoff);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.uploading);
        assertThat(batch.getCancelReason()).isNull();
        verify(importBatchRepository, never()).save(any());
        verify(importItemRepository, never()).saveAll(any());
    }

    @Test
    void abandoned_submittedMeanwhile_isLeftAlone() {
        ImportBatch batch = batch(ImportBatchStatus.queued, 0);
        stubLocked(batch, true);

        assertThat(job.expireAbandoned(batch.getImportBatchId(), OffsetDateTime.now())).isFalse();

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.queued);
        verify(importBatchRepository, never()).save(any());
    }

    // --- stuck batches ---

    @Test
    void stuckFirstTime_republishesSubmittedEventOnceAndRestartsWindow() {
        ImportBatch batch = batch(ImportBatchStatus.running, 0);
        batch.setUpdatedAt(OffsetDateTime.now().minusHours(5));
        stubLocked(batch, true);

        assertThat(job.recoverStuck(batch.getImportBatchId(), OffsetDateTime.now().minusHours(2))).isTrue();

        verify(outboxEventPublisher, times(1)).publish(batch.getTenantId(), "import_batch", batch.getImportBatchId(),
                "import_batch.submitted",
                Map.of("importBatchId", batch.getImportBatchId(), "correlationId", batch.getCorrelationId()));
        assertThat(batch.getRecoveryAttempts()).isEqualTo(1);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.running);
        assertThat(batch.getUpdatedAt()).isCloseTo(OffsetDateTime.now(), within(Duration.ofMinutes(1)));
        verify(importBatchRepository).save(batch);
        verify(importItemRepository, never()).saveAll(any());
        verify(importBatchRepository, never()).lockByImportBatchId(any());
    }

    @Test
    void stuckAgain_failsOnlyPendingRowsWithoutTicketAndFinalises() throws Exception {
        ImportBatch batch = batch(ImportBatchStatus.running, 1);
        String warning = objectMapper.writeValueAsString(List.of(
                new ImportRowError("notes", "SOME_WARNING", "Looks odd", ImportRowError.WARNING)));
        ImportItem unprocessed = item(batch, ImportItemStatus.pending, "imports/t/b/i1", null, warning);
        ImportItem noFile = item(batch, ImportItemStatus.pending, null, null, null);
        stubLocked(batch, true);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItemStatus.pending)).thenReturn(List.of(unprocessed, noFile));
        stubFinaliserCounts(batch, 3, 2, 0);

        assertThat(job.recoverStuck(batch.getImportBatchId(), OffsetDateTime.now().minusHours(2))).isTrue();

        assertThat(unprocessed.getStatus()).isEqualTo(ImportItemStatus.failed);
        assertThat(errors(unprocessed)).extracting(ImportRowError::code)
                .containsExactly("SOME_WARNING", "PROCESSING_ABANDONED");
        assertThat(errors(unprocessed).get(1).message())
                .isEqualTo("Processing stopped unexpectedly. Try again by resubmitting these rows.");
        assertThat(errors(unprocessed).get(1).severity()).isEqualTo(ImportRowError.ERROR);
        // Kept for a resubmit of the failed rows, like any data-failed row's.
        assertThat(unprocessed.getStagedObjectKey()).isEqualTo("imports/t/b/i1");
        assertThat(noFile.getStatus()).isEqualTo(ImportItemStatus.failed);
        assertThat(errors(noFile)).extracting(ImportRowError::code).containsExactly("PROCESSING_ABANDONED");
        verify(importItemRepository).saveAll(List.of(unprocessed, noFile));
        assertThat(batch.getRecoveryAttempts()).isEqualTo(2);
        verify(outboxEventPublisher, never()).publish(any(), any(), any(), any(), any());

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.completed_with_errors);
        assertThat(batch.getCompletedAt()).isNotNull();
        assertThat(batch.getSuccessCount()).isEqualTo(3);
        assertThat(batch.getFailureCount()).isEqualTo(2);
    }

    @Test
    void stuckAgain_rowsWaitingOnScanTicket_stayPendingAndBatchIsNotFinalised() {
        ImportBatch batch = batch(ImportBatchStatus.running, 1);
        ImportItem unprocessed = item(batch, ImportItemStatus.pending, null, null, null);
        ImportItem waiting = item(batch, ImportItemStatus.pending, null, UUID.randomUUID(), null);
        stubLocked(batch, true);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItemStatus.pending)).thenReturn(List.of(unprocessed, waiting));
        stubFinaliserCounts(batch, 0, 1, 1);

        job.recoverStuck(batch.getImportBatchId(), OffsetDateTime.now().minusHours(2));

        assertThat(unprocessed.getStatus()).isEqualTo(ImportItemStatus.failed);
        assertThat(waiting.getStatus()).isEqualTo(ImportItemStatus.pending);
        assertThat(waiting.getValidationErrors()).isNull();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.running);
        assertThat(batch.getCompletedAt()).isNull();
    }

    @Test
    void stuckAgain_batchStillQueued_isMovedToRunningAndFinalised() {
        ImportBatch batch = batch(ImportBatchStatus.queued, 1);
        ImportItem unprocessed = item(batch, ImportItemStatus.pending, "imports/t/b/i1", null, null);
        stubLocked(batch, true);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItemStatus.pending)).thenReturn(List.of(unprocessed));
        stubFinaliserCounts(batch, 0, 1, 0);

        job.recoverStuck(batch.getImportBatchId(), OffsetDateTime.now().minusHours(2));

        assertThat(unprocessed.getStatus()).isEqualTo(ImportItemStatus.failed);
        assertThat(batch.getStartedAt()).isNotNull();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.failed);
        assertThat(batch.getCompletedAt()).isNotNull();
    }

    @Test
    void stuck_recentActivityOnRecheck_isLeftAlone() {
        ImportBatch batch = batch(ImportBatchStatus.running, 1);
        stubLocked(batch, false);

        assertThat(job.recoverStuck(batch.getImportBatchId(), OffsetDateTime.now().minusHours(2))).isFalse();

        verify(outboxEventPublisher, never()).publish(any(), any(), any(), any(), any());
        verify(importItemRepository, never()).findAllByImportBatchIdAndStatusOrderByRowNumberAsc(any(), any());
        verify(importBatchRepository, never()).save(any());
        verify(importBatchRepository, never()).lockByImportBatchId(any());
    }

    @Test
    void finishedOrCancelledBatches_areNeverTouched() {
        for (ImportBatchStatus status : List.of(ImportBatchStatus.completed, ImportBatchStatus.completed_with_errors,
                ImportBatchStatus.failed, ImportBatchStatus.cancelled)) {
            ImportBatch batch = batch(status, 1);
            stubLocked(batch, true);

            assertThat(job.expireAbandoned(batch.getImportBatchId(), OffsetDateTime.now())).isFalse();
            assertThat(job.recoverStuck(batch.getImportBatchId(), OffsetDateTime.now())).isFalse();

            assertThat(batch.getStatus()).isEqualTo(status);
        }
        verify(importBatchRepository, never()).save(any());
        verify(importItemRepository, never()).saveAll(any());
        verify(outboxEventPublisher, never()).publish(any(), any(), any(), any(), any());
        verify(importBatchRepository, never()).lockByImportBatchId(any());
    }

    private static OffsetDateTime cutoffAround(int hoursAgo) {
        OffsetDateTime expected = OffsetDateTime.now().minusHours(hoursAgo);
        return argThat(cutoff -> cutoff != null
                && Duration.between(cutoff, expected).abs().compareTo(Duration.ofMinutes(1)) < 0);
    }

    private void stubLocked(ImportBatch batch, boolean inactive) {
        when(importBatchRepository.lockForRecovery(batch.getImportBatchId())).thenReturn(Optional.of(batch));
        when(importBatchRepository.isInactiveSince(eq(batch.getImportBatchId()), any())).thenReturn(inactive);
    }

    /** What the real finaliser reads once the give-up has committed. */
    private void stubFinaliserCounts(ImportBatch batch, long succeeded, long failed, long pending) {
        when(importBatchRepository.lockByImportBatchId(batch.getImportBatchId())).thenReturn(Optional.of(batch));
        when(importItemRepository.countByImportBatchIdAndStatus(batch.getImportBatchId(), ImportItemStatus.success))
                .thenReturn(succeeded);
        when(importItemRepository.countByImportBatchIdAndStatus(batch.getImportBatchId(), ImportItemStatus.failed))
                .thenReturn(failed);
        when(importItemRepository.countByImportBatchIdAndStatus(batch.getImportBatchId(), ImportItemStatus.pending))
                .thenReturn(pending);
    }

    private List<ImportRowError> errors(ImportItem item) throws Exception {
        return objectMapper.readValue(item.getValidationErrors(), new TypeReference<>() {
        });
    }

    private static ImportBatch batch(ImportBatchStatus status, int recoveryAttempts) {
        return ImportBatch.builder()
                .importBatchId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .sourceType(ImportBatch.SourceType.bulk_import)
                .documentTypeId(UUID.randomUUID())
                .schemaVersion(1)
                .status(status)
                .recoveryAttempts(recoveryAttempts)
                .correlationId(UUID.randomUUID())
                .createdBy(UUID.randomUUID())
                .build();
    }

    private ImportItem item(ImportBatch batch, ImportItemStatus status, String stagedObjectKey, UUID scanResultId,
                            String validationErrors) {
        return ImportItem.builder()
                .importItemId(UUID.randomUUID())
                .importBatchId(batch.getImportBatchId())
                .status(status)
                .stagedObjectKey(stagedObjectKey)
                .stagedStorageLocationId(stagedObjectKey != null ? location.getStorageLocationId() : null)
                .stagedSizeBytes(stagedObjectKey != null ? 10L : null)
                .scanResultId(scanResultId)
                .validationErrors(validationErrors)
                .build();
    }
}
