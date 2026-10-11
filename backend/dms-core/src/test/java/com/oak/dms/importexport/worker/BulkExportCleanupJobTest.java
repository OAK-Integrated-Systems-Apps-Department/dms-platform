package com.oak.dms.importexport.worker;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.enums.AuditActorType;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.importexport.AuditTestSupport;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.entity.ExportJob.ExportJobStatus;
import com.oak.dms.importexport.repository.ExportJobRepository;
import com.oak.dms.importexport.service.impl.BulkExportServiceImpl;
import com.oak.dms.outbox.OutboxEventPublisher;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BulkExportCleanupJobTest {

    private final ExportJobRepository exportJobRepository = mock(ExportJobRepository.class);
    private final StorageLocationRepository storageLocationRepository = mock(StorageLocationRepository.class);
    private final ObjectStorageServiceFactory objectStorageServiceFactory = mock(ObjectStorageServiceFactory.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final OutboxEventPublisher outboxEventPublisher = mock(OutboxEventPublisher.class);
    private final AuditService auditService = AuditTestSupport.mockAuditService();
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ImportExportProperties properties = new ImportExportProperties();
    private final StorageLocation location = StorageLocation.builder().storageLocationId(UUID.randomUUID()).build();

    private final BulkExportCleanupJob cleanupJob = new BulkExportCleanupJob(exportJobRepository,
            storageLocationRepository, objectStorageServiceFactory, outboxEventPublisher,
            new BulkExportCompletion(exportJobRepository, outboxEventPublisher, auditService, properties),
            properties, transactionManager);

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(storageLocationRepository.findByStorageLocationIdAndDeletedFalse(location.getStorageLocationId()))
                .thenReturn(Optional.of(location));
        when(objectStorageServiceFactory.resolve(location)).thenReturn(storage);
        when(exportJobRepository.findExportIdsDueExpiryWarning(any(), any(), any())).thenReturn(List.of());
        when(exportJobRepository.findExpiredExportIds(any(), any())).thenReturn(List.of());
        when(exportJobRepository.findInactiveExportIds(any(), any(), any())).thenReturn(List.of());
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ---- expiry warning

    @Test
    void cleanUp_queriesWarningWindowExpiryAndInactivityCutoffFromConfig() {
        properties.setExportExpiryWarningHours(3);
        properties.setStuckExportHours(5);

        cleanupJob.cleanUp();

        ArgumentCaptor<OffsetDateTime> now = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> warnBefore = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(exportJobRepository).findExportIdsDueExpiryWarning(now.capture(), warnBefore.capture(),
                any(Pageable.class));
        assertThat(warnBefore.getValue()).isCloseTo(now.getValue().plusHours(3), within(Duration.ofSeconds(1)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ExportJobStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(exportJobRepository).findInactiveExportIds(statuses.capture(), cutoff.capture(), any(Pageable.class));
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(ExportJobStatus.pending, ExportJobStatus.processing);
        assertThat(cutoff.getValue()).isCloseTo(OffsetDateTime.now().minusHours(5), within(Duration.ofMinutes(1)));
    }

    @Test
    void warn_dueJob_publishesExpiringSoonOnceAndRecordsIt() {
        ExportJob job = completedJob(OffsetDateTime.now().plusMinutes(90));
        when(exportJobRepository.findExportIdsDueExpiryWarning(any(), any(), any()))
                .thenReturn(List.of(job.getExportJobId()));
        stubLock(job);

        cleanupJob.cleanUp();

        assertThat(job.getExpiryWarningSentAt()).isNotNull();
        verify(outboxEventPublisher).publish(tenantOf(job), BulkExportServiceImpl.AGGREGATE_TYPE,
                job.getExportJobId(), BulkExportServiceImpl.EXPIRING_SOON_EVENT_TYPE,
                BulkExportCleanupJob.expiringSoonPayload(job));

        // Already warned: a second pass (the candidate query raced) publishes nothing more.
        cleanupJob.cleanUp();
        verify(outboxEventPublisher).publish(any(), anyString(), any(), eq(BulkExportServiceImpl.EXPIRING_SOON_EVENT_TYPE),
                any());
    }

    @Test
    void warn_notYetInsideTheWindowOrAlreadyExpired_isLeftAlone() {
        ExportJob later = completedJob(OffsetDateTime.now().plusHours(10));
        ExportJob lapsed = completedJob(OffsetDateTime.now().minusMinutes(1));
        stubLock(later);
        stubLock(lapsed);

        assertThat(cleanupJob.warn(later.getExportJobId(), OffsetDateTime.now())).isFalse();
        assertThat(cleanupJob.warn(lapsed.getExportJobId(), OffsetDateTime.now())).isFalse();
        assertThat(later.getExpiryWarningSentAt()).isNull();
        verify(outboxEventPublisher, never()).publish(any(), anyString(), any(), anyString(), any());
    }

    // ---- expiry

    @Test
    void expire_pastExpiry_marksExpiredAndDeletesTheZipAfterCommit() {
        ExportJob job = completedJob(OffsetDateTime.now().minusMinutes(5));
        String objectKey = job.getFileObjectKey();
        when(exportJobRepository.findExpiredExportIds(any(), any())).thenReturn(List.of(job.getExportJobId()));
        stubLock(job);

        cleanupJob.cleanUp();

        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.expired);
        assertThat(job.getFileObjectKey()).isNull();
        assertThat(job.getFileStorageLocationId()).isNull();
        verify(exportJobRepository).save(job);
        verify(storage, never()).delete(any(), anyString());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(storage).delete(location, objectKey);
    }

    @Test
    void expire_notYetExpiredOnRecheck_isLeftAlone() {
        ExportJob job = completedJob(OffsetDateTime.now().plusHours(1));
        stubLock(job);

        assertThat(cleanupJob.expire(job.getExportJobId(), OffsetDateTime.now())).isFalse();
        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.completed);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void lockedJob_isSkippedThisPass() {
        ExportJob job = completedJob(OffsetDateTime.now().minusMinutes(5));
        when(exportJobRepository.findExpiredExportIds(any(), any())).thenReturn(List.of(job.getExportJobId()));
        when(exportJobRepository.lockForCleanup(job.getExportJobId())).thenReturn(Optional.empty());

        cleanupJob.cleanUp();

        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.completed);
        verify(exportJobRepository, never()).save(any());
    }

    // ---- stuck jobs

    @Test
    void failStuck_noActivityForTheWindow_failsWithReasonEventAndAudit() {
        ExportJob job = unfinishedJob(ExportJobStatus.processing, OffsetDateTime.now().minusHours(5),
                OffsetDateTime.now().minusHours(3));
        when(exportJobRepository.findInactiveExportIds(any(), any(), any())).thenReturn(List.of(job.getExportJobId()));
        stubLock(job);

        cleanupJob.cleanUp();

        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.failed);
        assertThat(job.getFailureReason()).isEqualTo(BulkExportCleanupJob.REASON_STUCK);
        verify(outboxEventPublisher).publish(tenantOf(job), BulkExportServiceImpl.AGGREGATE_TYPE,
                job.getExportJobId(), BulkExportServiceImpl.COMPLETED_EVENT_TYPE,
                BulkExportCompletion.completedPayload(job));
        AuditEventBuilder event = AuditTestSupport.recorded(auditService).get(0);
        assertThat(event.action()).isEqualTo(AuditActionCatalog.BULK_EXPORT_COMPLETED);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.actorType()).isEqualTo(AuditActorType.SYSTEM);
    }

    @Test
    void failStuck_pendingJobNeverTouched_goesByCreatedAt() {
        ExportJob job = unfinishedJob(ExportJobStatus.pending, OffsetDateTime.now().minusHours(3), null);
        stubLock(job);

        assertThat(cleanupJob.failStuck(job.getExportJobId(), OffsetDateTime.now().minusHours(2))).isTrue();
        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.failed);
    }

    @Test
    void failStuck_longExportStillMoving_isNotFailed() {
        // Started long ago, but the worker's heartbeat touched it a minute ago.
        ExportJob job = unfinishedJob(ExportJobStatus.processing, OffsetDateTime.now().minusHours(6),
                OffsetDateTime.now().minusMinutes(1));
        job.setStartedAt(OffsetDateTime.now().minusHours(6));
        stubLock(job);

        // Even if the candidate query had returned it (it raced a heartbeat), the re-check under the lock keeps it.
        assertThat(cleanupJob.failStuck(job.getExportJobId(), OffsetDateTime.now().minusHours(2))).isFalse();
        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.processing);
        verify(outboxEventPublisher, never()).publish(any(), anyString(), any(), anyString(), any());
    }

    @Test
    void failStuck_jobFinishedMeanwhile_isLeftAlone() {
        ExportJob job = completedJob(OffsetDateTime.now().plusHours(20));
        job.setUpdatedAt(OffsetDateTime.now().minusHours(5));
        stubLock(job);

        assertThat(cleanupJob.failStuck(job.getExportJobId(), OffsetDateTime.now().minusHours(2))).isFalse();
        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.completed);
    }

    private ExportJob completedJob(OffsetDateTime expiresAt) {
        UUID tenantId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        return ExportJob.builder()
                .exportJobId(id)
                .tenantId(tenantId)
                .exportType(ExportJob.ExportType.bulk_selection)
                .metadataFormat(ExportJob.MetadataFormat.xlsx)
                .requestParams("{}")
                .status(ExportJobStatus.completed)
                .documentCount(4)
                .fileObjectKey("exports/" + tenantId + "/" + id + ".zip")
                .fileStorageLocationId(location.getStorageLocationId())
                .fileSizeBytes(1024L)
                .completedAt(expiresAt.minusHours(24))
                .expiresAt(expiresAt)
                .correlationId(UUID.randomUUID())
                .createdBy(UUID.randomUUID())
                .build();
    }

    private ExportJob unfinishedJob(ExportJobStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        return ExportJob.builder()
                .exportJobId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .exportType(ExportJob.ExportType.bulk_selection)
                .metadataFormat(ExportJob.MetadataFormat.csv)
                .requestParams("{}")
                .status(status)
                .correlationId(UUID.randomUUID())
                .createdBy(UUID.randomUUID())
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    private void stubLock(ExportJob job) {
        when(exportJobRepository.lockForCleanup(job.getExportJobId())).thenReturn(Optional.of(job));
    }

    private static UUID tenantOf(ExportJob job) {
        return job.getTenantId();
    }
}
