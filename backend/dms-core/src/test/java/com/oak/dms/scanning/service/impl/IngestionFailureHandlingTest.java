package com.oak.dms.scanning.service.impl;

import com.oak.dms.audit.service.AuditService;
import com.oak.dms.observability.DmsMetrics;
import com.oak.dms.scanning.dto.DetectedFileType;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.entity.ScanEngine;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.ScanMode;
import com.oak.dms.scanning.enums.ScanProvider;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.helper.FileTypeInspector;
import com.oak.dms.scanning.helper.IngestionProperties;
import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.scanning.helper.StorageObjectMover;
import com.oak.dms.scanning.repository.QuarantineRecordRepository;
import com.oak.dms.scanning.repository.ScanResultRepository;
import com.oak.dms.scanning.service.DocumentPromoter;
import com.oak.dms.scanning.service.ScanEngineFactory;
import com.oak.dms.scanning.service.ScanEngineResolver;
import com.oak.dms.scanning.helper.IngestionTicketMapper;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DOC-13, the gate and the processor's cleanup contract, with the collaborators mocked so each branch is exact.
 * The same behaviour against the real database and storage is in {@code IngestionFailureCleanupIT}.
 */
class IngestionFailureHandlingTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final OffsetDateTime lease = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS).plusMinutes(30);

    private ScanResultRepository scanResults;
    private StorageObjectMover mover;
    private StorageLocationRepository locations;
    private PlatformTransactionManager transactions;
    private IngestionProcessor processor;
    private final StorageLocation stagingLocation = StorageLocation.builder().storageLocationId(UUID.randomUUID())
            .purpose(StoragePurpose.staging).build();

    @BeforeEach
    void setUp() {
        scanResults = mock(ScanResultRepository.class);
        mover = mock(StorageObjectMover.class);
        locations = mock(StorageLocationRepository.class);
        transactions = mock(PlatformTransactionManager.class);
        processor = new IngestionProcessor(scanResults, mock(QuarantineRecordRepository.class),
                mock(ScanEngineResolver.class), mock(ScanEngineFactory.class), locations,
                mock(StorageLocationResolver.class), mover, mock(DocumentPromoter.class),
                mock(AuditService.class, RETURNS_DEEP_STUBS), new ScanProperties(),
                mock(ReleaseAttemptFinalizer.class), mock(ApplicationEventPublisher.class), mock(DmsMetrics.class),
                transactions);
        when(locations.findByStorageLocationIdAndDeletedFalse(stagingLocation.getStorageLocationId()))
                .thenReturn(Optional.of(stagingLocation));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // IngestionProcessor.abandonUnacceptedSubmission
    // ------------------------------------------------------------------

    private ScanResult row(ScanResultStatus status, OffsetDateTime rowLease) {
        ScanResult row = ScanResult.builder().scanResultId(UUID.randomUUID()).tenantId(tenantId).status(status)
                .leaseExpiresAt(rowLease).stagingLocationId(stagingLocation.getStorageLocationId())
                .stagingObjectKey("staging/" + tenantId + "/x").build();
        when(scanResults.findById(row.getScanResultId())).thenReturn(Optional.of(row));
        when(scanResults.lockById(row.getScanResultId())).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void abandoningARowThisRequestStillHoldsFailsItAndRemovesItsStagedObject() {
        ScanResult row = row(ScanResultStatus.pending, lease);
        when(mover.deleteQuietly(any(), anyString())).thenReturn(true);

        processor.abandonUnacceptedSubmission(row.getScanResultId(), lease, "Ingestion failed before it was accepted: X");

        assertThat(row.getStatus()).isEqualTo(ScanResultStatus.failed);
        assertThat(row.getLeaseExpiresAt()).isNull();
        assertThat(row.getNextAttemptAt()).isNull();
        assertThat(row.getFailureReason()).startsWith("Ingestion failed before it was accepted");
        verify(mover).deleteQuietly(stagingLocation, row.getStagingObjectKey());
        assertThat(row.getStagingPurgedAt()).isNotNull();
    }

    @Test
    void abandoningARowAWorkerHasClaimedChangesNothingAndDeletesNothing() {
        ScanResult row = row(ScanResultStatus.pending, lease.plusHours(1));        // the worker's lease, not ours

        processor.abandonUnacceptedSubmission(row.getScanResultId(), lease, "x");

        assertThat(row.getStatus()).isEqualTo(ScanResultStatus.pending);
        assertThat(row.getLeaseExpiresAt()).isEqualTo(lease.plusHours(1));
        verify(mover, never()).deleteQuietly(any(), anyString());
        assertThat(row.getStagingPurgedAt()).isNull();
    }

    @Test
    void abandoningARowThatAlreadySettledChangesNothing() {
        for (ScanResultStatus settled : new ScanResultStatus[]{ScanResultStatus.clean, ScanResultStatus.failed,
                ScanResultStatus.quarantined, ScanResultStatus.unscannable}) {
            ScanResult row = row(settled, null);
            row.setStagingPurgedAt(OffsetDateTime.now());

            processor.abandonUnacceptedSubmission(row.getScanResultId(), lease, "x");

            assertThat(row.getStatus()).isEqualTo(settled);
        }
        verify(mover, never()).deleteQuietly(any(), anyString());
    }

    @Test
    void abandoningAMissingRowDoesNothing() {
        processor.abandonUnacceptedSubmission(UUID.randomUUID(), lease, "x");

        verify(mover, never()).deleteQuietly(any(), anyString());
    }

    @Test
    void ifTheStagedObjectCannotBeRemovedTheRowIsStillFailedAndStaysEligibleForTheRetentionSweep() {
        ScanResult row = row(ScanResultStatus.pending, lease);
        when(mover.deleteQuietly(any(), anyString())).thenReturn(false);

        processor.abandonUnacceptedSubmission(row.getScanResultId(), lease, "x");

        assertThat(row.getStatus()).isEqualTo(ScanResultStatus.failed);
        assertThat(row.getStagingPurgedAt()).as("not marked purged, so the existing sweep still finds it").isNull();
    }

    // ------------------------------------------------------------------
    // IngestionGateImpl.submit
    // ------------------------------------------------------------------

    private IngestionProcessor gateProcessor;
    private ObjectStorageService stagingStore;
    private IngestionGateImpl gate;
    private ScanResult created;

    private void gate() throws Exception {
        gateProcessor = mock(IngestionProcessor.class);
        stagingStore = mock(ObjectStorageService.class);
        ScanEngineResolver engines = mock(ScanEngineResolver.class);
        ScanEngine engine = mock(ScanEngine.class);
        when(engine.getMaxFileSizeBytes()).thenReturn(10_000_000L);
        when(engine.getScanMode()).thenReturn(ScanMode.pre_store_stream);
        when(engine.getProvider()).thenReturn(ScanProvider.CLAMAV);
        when(engines.resolveForTenant(tenantId)).thenReturn(engine);
        StorageLocationResolver resolver = mock(StorageLocationResolver.class);
        when(resolver.resolveForTenant(tenantId, StoragePurpose.staging)).thenReturn(stagingLocation);
        ObjectStorageServiceFactory factory = mock(ObjectStorageServiceFactory.class);
        when(factory.resolve(stagingLocation)).thenReturn(stagingStore);
        FileTypeInspector inspector = mock(FileTypeInspector.class);
        when(inspector.inspect(any(), any(), anyInt())).thenReturn(
                new DetectedFileType("pdf", "application/pdf", false, false));
        when(scanResults.saveAndFlush(any(ScanResult.class))).thenAnswer(call -> {
            ScanResult saved = call.getArgument(0);
            if (saved.getScanResultId() == null) {
                saved.setScanResultId(UUID.randomUUID());
            }
            created = saved;
            return saved;
        });
        when(scanResults.findById(any(UUID.class))).thenAnswer(call -> Optional.ofNullable(created));
        gate = new IngestionGateImpl(engines, mock(ScanEngineFactory.class), resolver, factory, scanResults, inspector,
                gateProcessor, mock(ScanWorkPool.class), mover, mock(DocumentPromoter.class),
                mock(IngestionTicketMapper.class), new ScanProperties(), new IngestionProperties(), transactions,
                mock(JdbcTemplate.class));
        TenantContext.set(tenantId, userId);
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private IngestionRequest request() {
        byte[] body = "%PDF-1.4 test".getBytes(StandardCharsets.ISO_8859_1);
        return IngestionRequest.newVersion(UUID.randomUUID(), "replacement_upload", "a.pdf", body.length,
                new ByteArrayInputStream(body), null);
    }

    @Test
    void anUncheckedFailureAfterTheRowExistsAbandonsThatRowUnderItsOwnLeaseAndRethrowsTheSameException() throws Exception {
        gate();
        UncheckedIOException failure = new UncheckedIOException(new IOException("boom"));
        when(stagingStore.upload(any(), anyString(), any(), anyLong(), anyString())).thenThrow(failure);

        assertThatThrownBy(() -> gate.submit(request())).isSameAs(failure);

        verify(gateProcessor).abandonUnacceptedSubmission(eq(created.getScanResultId()), eq(created.getLeaseExpiresAt()),
                anyString());
        verify(gateProcessor, never()).process(any());
    }

    @Test
    void aFailureOfTheCleanupItselfNeverReplacesTheOriginalException() throws Exception {
        gate();
        UncheckedIOException failure = new UncheckedIOException(new IOException("boom"));
        IllegalStateException cleanupFailure = new IllegalStateException("db down");
        when(stagingStore.upload(any(), anyString(), any(), anyLong(), anyString())).thenThrow(failure);
        doThrow(cleanupFailure).when(gateProcessor).abandonUnacceptedSubmission(any(), any(), anyString());

        assertThatThrownBy(() -> gate.submit(request())).isSameAs(failure).hasSuppressedException(cleanupFailure);
    }

    @Test
    void afterTheHandOffToTheWorkerTheGateNoLongerCleansUp() throws Exception {
        gate();
        when(stagingStore.upload(any(), anyString(), any(), anyLong(), anyString())).thenReturn(
                new StoredObjectMetadata("k", 13, "application/pdf", "etag", OffsetDateTime.now()));
        IllegalStateException failure = new IllegalStateException("after acceptance");
        when(gateProcessor.process(any())).thenThrow(failure);

        assertThatThrownBy(() -> gate.submit(request())).isSameAs(failure);

        verify(gateProcessor, never()).abandonUnacceptedSubmission(any(), any(), anyString());
    }

    @Test
    void aSubmissionThatSucceedsIsNeverAbandoned() throws Exception {
        gate();
        when(stagingStore.upload(any(), anyString(), any(), anyLong(), anyString())).thenReturn(
                new StoredObjectMetadata("k", 13, "application/pdf", "etag", OffsetDateTime.now()));

        gate.submit(request());

        verify(gateProcessor, times(1)).process(created.getScanResultId());
        verify(gateProcessor, never()).abandonUnacceptedSubmission(any(), any(), anyString());
    }

    @Test
    void aFailureBeforeTheRowExistsHasNothingToAbandon() throws Exception {
        gate();
        IllegalStateException failure = new IllegalStateException("no engine");
        when(scanResults.saveAndFlush(any(ScanResult.class))).thenThrow(failure);

        assertThatThrownBy(() -> gate.submit(request())).isSameAs(failure);

        verify(gateProcessor, never()).abandonUnacceptedSubmission(any(), any(), anyString());
        verify(stagingStore, never()).upload(any(), anyString(), any(), anyLong(), anyString());
    }
}
