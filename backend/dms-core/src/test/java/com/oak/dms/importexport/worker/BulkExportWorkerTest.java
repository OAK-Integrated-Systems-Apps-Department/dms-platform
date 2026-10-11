package com.oak.dms.importexport.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.enums.AuditActorType;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.KeycloakIntegrationException;
import com.oak.dms.document.DocumentNotScannedException;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.importexport.AuditTestSupport;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.entity.ExportJob.ExportJobStatus;
import com.oak.dms.importexport.entity.ExportJob.MetadataFormat;
import com.oak.dms.importexport.export.BulkExportAccessPolicy;
import com.oak.dms.importexport.export.BulkExportRequestParams;
import com.oak.dms.importexport.export.ExportMetadataAssembler;
import com.oak.dms.importexport.export.ExportableDocumentQuery;
import com.oak.dms.importexport.export.ExportableDocumentRow;
import com.oak.dms.importexport.repository.ExportJobRepository;
import com.oak.dms.importexport.service.impl.BulkExportServiceImpl;
import com.oak.dms.importexport.validation.ImportSchemaFields;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import com.oak.dms.metadata.repository.MetadataFieldOptionRepository;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BulkExportWorkerTest {

    private final ExportJobRepository exportJobRepository = mock(ExportJobRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RolePermissionRepository rolePermissionRepository = mock(RolePermissionRepository.class);
    private final KeycloakUserService keycloakUserService = mock(KeycloakUserService.class);
    private final ExportableDocumentQuery query = mock(ExportableDocumentQuery.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final DocumentMetadataRepository documentMetadataRepository = mock(DocumentMetadataRepository.class);
    private final StorageLocationResolver storageLocationResolver = mock(StorageLocationResolver.class);
    private final ObjectStorageServiceFactory objectStorageServiceFactory = mock(ObjectStorageServiceFactory.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final OutboxEventPublisher outboxEventPublisher = mock(OutboxEventPublisher.class);
    private final AuditService auditService = AuditTestSupport.mockAuditService();
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ImportExportProperties properties = new ImportExportProperties();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final BulkExportWorker worker = new BulkExportWorker(exportJobRepository, userRepository,
            new BulkExportAccessPolicy(rolePermissionRepository, userRepository, keycloakUserService), query,
            documentService,
            new ExportMetadataAssembler(documentMetadataRepository, mock(ImportSchemaFields.class),
                    mock(MetadataFieldOptionRepository.class), userRepository),
            storageLocationResolver, objectStorageServiceFactory,
            new BulkExportCompletion(exportJobRepository, outboxEventPublisher, auditService, properties),
            objectMapper, transactionManager);

    @TempDir
    Path tempDir;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID keycloakId = UUID.randomUUID();
    private final StorageLocation staging = StorageLocation.builder().storageLocationId(UUID.randomUUID()).build();
    private final AtomicReference<byte[]> uploaded = new AtomicReference<>();
    private ExportJob job;

    @BeforeEach
    void setUp() throws Exception {
        worker.setTempDirectory(tempDir);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(userRepository.findByUserIdAndTenantIdAndDeletedFalse(userId, tenantId)).thenReturn(Optional.of(
                User.builder().userId(userId).tenantId(tenantId).keycloakId(keycloakId).displayName("Asha Perera")
                        .build()));
        when(rolePermissionRepository.findEffectivePermissionCodes(userId, tenantId))
                .thenReturn(List.of(PermissionCodes.FIE_BULK_EXPORT_EXECUTE, PermissionCodes.DOCM_DOCUMENTS_VIEW));
        when(documentMetadataRepository.findAllById(anyIterable())).thenReturn(List.of());
        when(storageLocationResolver.resolveForTenant(tenantId, StoragePurpose.staging)).thenReturn(staging);
        when(objectStorageServiceFactory.resolve(staging)).thenReturn(storage);
        when(storage.upload(eq(staging), anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    uploaded.set(invocation.<InputStream>getArgument(2).readAllBytes());
                    return new StoredObjectMetadata(invocation.getArgument(1), uploaded.get().length,
                            "application/zip", null, OffsetDateTime.now());
                });
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
        TenantContext.clear();
    }

    // ---- message and claim

    @Test
    void parseExportJobId_doubleEncodedOrPlain_readsTheId() throws Exception {
        UUID id = UUID.randomUUID();
        String payload = "{\"exportJobId\":\"" + id + "\",\"correlationId\":\"" + UUID.randomUUID() + "\"}";

        assertThat(worker.parseExportJobId(objectMapper.writeValueAsBytes(payload))).isEqualTo(id);
        assertThat(worker.parseExportJobId(payload.getBytes(StandardCharsets.UTF_8))).isEqualTo(id);
        for (String body : List.of("not json", "{}", "{\"exportJobId\":\"nope\"}")) {
            assertThatThrownBy(() -> worker.parseExportJobId(body.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
    }

    @Test
    void claim_pendingJob_movesToProcessing() {
        job = job(MetadataFormat.xlsx, List.of());
        job.setStatus(ExportJobStatus.pending);
        stubLock();

        Optional<BulkExportWorker.ClaimedExport> claimed = worker.claim(job.getExportJobId());

        assertThat(claimed).isPresent();
        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.processing);
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(job.getUpdatedAt()).isNotNull();
        verify(exportJobRepository).save(job);
    }

    @Test
    void claim_processingJob_isBuiltAgain_finishedJobIsSkipped() {
        job = job(MetadataFormat.xlsx, List.of());
        stubLock();
        assertThat(worker.claim(job.getExportJobId())).isPresent();

        for (ExportJobStatus status : List.of(ExportJobStatus.completed, ExportJobStatus.failed,
                ExportJobStatus.expired)) {
            job.setStatus(status);
            assertThat(worker.claim(job.getExportJobId())).isEmpty();
        }
    }

    // ---- building

    @Test
    void process_buildsTheZipWithNumberedDuplicatesSkipsWithReasonsAndCompletes() throws Exception {
        ExportableDocumentRow first = row("report.pdf", "clean");
        ExportableDocumentRow second = row("report.pdf", "overridden");
        ExportableDocumentRow third = row("Report.PDF", "clean");
        ExportableDocumentRow deleted = deletedRow("gone.pdf");
        ExportableDocumentRow unreadable = row("broken.pdf", "clean");
        ExportableDocumentRow raced = row("raced.pdf", "clean");
        UUID missing = UUID.randomUUID();
        job = job(MetadataFormat.xlsx, List.of(first.documentId(), deleted.documentId(), second.documentId(),
                missing, third.documentId(), unreadable.documentId(), raced.documentId()));
        stubLock();
        when(query.loadForExport(any())).thenReturn(List.of(first, second, third, deleted, unreadable, raced));
        stubContent(first, "one");
        stubContent(second, "two");
        stubContent(third, "three");
        when(documentService.downloadDocument(unreadable.documentId())).thenReturn(new DownloadedDocument(
                failingStream(), 10, "application/pdf", "broken.pdf"));
        when(documentService.downloadDocument(raced.documentId()))
                .thenThrow(new DocumentNotScannedException(raced.documentId()));

        worker.process(claimed());

        Map<String, byte[]> zip = unzip(uploaded.get());
        assertThat(zip.keySet()).containsExactly("report.pdf", "report (2).pdf", "Report (3).PDF", "metadata.xlsx");
        assertThat(new String(zip.get("report (2).pdf"), StandardCharsets.UTF_8)).isEqualTo("two");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(zip.get("metadata.xlsx")))) {
            assertThat(workbook.getSheet("Documents").getLastRowNum()).isEqualTo(3);
            assertThat(workbook.getSheet("Documents").getRow(2).getCell(0).getStringCellValue())
                    .isEqualTo("report (2).pdf");
            assertThat(workbook.getSheet("Documents").getRow(2).getCell(7).getStringCellValue())
                    .isEqualTo("Released by administrator");
            var skippedSheet = workbook.getSheet("Skipped");
            List<String> reasons = new ArrayList<>();
            for (int i = 1; i <= skippedSheet.getLastRowNum(); i++) {
                reasons.add(skippedSheet.getRow(i).getCell(0).getStringCellValue() + " | "
                        + skippedSheet.getRow(i).getCell(1).getStringCellValue());
            }
            assertThat(reasons).containsExactly(
                    "gone.pdf | The document has been deleted",
                    missing + " | " + BulkExportWorker.SKIP_NOT_FOUND,
                    "broken.pdf | " + BulkExportWorker.SKIP_UNREADABLE,
                    "raced.pdf | " + BulkExportWorker.SKIP_NOT_SCANNED);
            var about = workbook.getSheet("About this export");
            assertThat(about.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Asha Perera");
        }

        verify(storage).upload(eq(staging), eq("exports/" + tenantId + "/" + job.getExportJobId() + ".zip"),
                any(InputStream.class), eq((long) uploaded.get().length), eq("application/zip"));
        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.completed);
        assertThat(job.getDocumentCount()).isEqualTo(3);
        assertThat(job.getSkippedCount()).isEqualTo(4);
        assertThat(job.getFileObjectKey()).isEqualTo("exports/" + tenantId + "/" + job.getExportJobId() + ".zip");
        assertThat(job.getFileStorageLocationId()).isEqualTo(staging.getStorageLocationId());
        assertThat(job.getFileSizeBytes()).isEqualTo(uploaded.get().length);
        assertThat(job.getCompletedAt()).isNotNull();
        assertThat(job.getExpiresAt()).isCloseTo(OffsetDateTime.now().plusHours(24), within(Duration.ofMinutes(1)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exportJobId", job.getExportJobId());
        payload.put("tenantId", tenantId);
        payload.put("requestedBy", userId);
        payload.put("status", "completed");
        payload.put("documentCount", 3);
        payload.put("skippedCount", 4);
        payload.put("correlationId", job.getCorrelationId());
        verify(outboxEventPublisher).publish(tenantId, BulkExportServiceImpl.AGGREGATE_TYPE, job.getExportJobId(),
                BulkExportServiceImpl.COMPLETED_EVENT_TYPE, payload);

        List<AuditEventBuilder> events = AuditTestSupport.recorded(auditService);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).action()).isEqualTo(AuditActionCatalog.BULK_EXPORT_COMPLETED);
        assertThat(events.get(0).actorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(events.get(0).outcome()).isEqualTo(AuditOutcome.SUCCESS);

        assertTempDirEmpty();
        assertThat(tenantContextIsClear()).isTrue();
    }

    @Test
    void process_readsEachDocumentAsTheRequesterWithTheirName() {
        ExportableDocumentRow doc = row("a.pdf", "clean");
        job = job(MetadataFormat.xlsx, List.of(doc.documentId()));
        stubLock();
        when(query.loadForExport(any())).thenReturn(List.of(doc));
        List<String> seen = new ArrayList<>();
        when(documentService.downloadDocument(doc.documentId())).thenAnswer(invocation -> {
            seen.add(TenantContext.getTenantId() + "/" + TenantContext.getUserId() + "/"
                    + TenantContext.getDisplayNameOrNull());
            return content("a");
        });

        worker.process(claimed());

        assertThat(seen).containsExactly(tenantId + "/" + userId + "/Asha Perera");
    }

    @Test
    void process_csvFormat_writesMetadataAndSkippedCsvWithBom() throws Exception {
        ExportableDocumentRow doc = row("=evil.pdf", "clean");
        ExportableDocumentRow deleted = deletedRow("old, draft.pdf");
        job = job(MetadataFormat.csv, List.of(doc.documentId(), deleted.documentId()));
        stubLock();
        when(query.loadForExport(any())).thenReturn(List.of(doc, deleted));
        stubContent(doc, "x");

        worker.process(claimed());

        Map<String, byte[]> zip = unzip(uploaded.get());
        assertThat(zip.keySet()).containsExactly("=evil.pdf", "metadata.csv", "skipped.csv");
        byte[] metadata = zip.get("metadata.csv");
        assertThat(metadata).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String metadataText = new String(metadata, 3, metadata.length - 3, StandardCharsets.UTF_8);
        assertThat(metadataText.split("\n")[1]).startsWith("'=evil.pdf,'=evil.pdf,");
        byte[] skipped = zip.get("skipped.csv");
        assertThat(skipped).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(new String(skipped, 3, skipped.length - 3, StandardCharsets.UTF_8))
                .isEqualTo("Title,Reason\n\"old, draft.pdf\",The document has been deleted\n");
        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.completed);
    }

    @Test
    void process_nothingCouldBeExported_failsWithTheReasonsAndUploadsNothing() throws Exception {
        ExportableDocumentRow deleted = deletedRow("a.pdf");
        ExportableDocumentRow infected = row("b.pdf", "infected");
        job = job(MetadataFormat.xlsx, List.of(deleted.documentId(), infected.documentId()));
        stubLock();
        when(query.loadForExport(any())).thenReturn(List.of(deleted, infected));

        worker.process(claimed());

        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.failed);
        assertThat(job.getFailureReason()).isEqualTo("None of the 2 selected documents could be exported"
                + " (The document has been deleted: 1; The current version has not passed the malware scan: 1).");
        assertThat(job.getSkippedCount()).isEqualTo(2);
        assertThat(job.getDocumentCount()).isZero();
        verify(storage, never()).upload(any(), anyString(), any(), anyLong(), anyString());
        verifyNoInteractions(documentService);
        verify(outboxEventPublisher).publish(eq(tenantId), eq(BulkExportServiceImpl.AGGREGATE_TYPE),
                eq(job.getExportJobId()), eq(BulkExportServiceImpl.COMPLETED_EVENT_TYPE),
                eq(BulkExportCompletion.completedPayload(job)));
        AuditEventBuilder event = AuditTestSupport.recorded(auditService).get(0);
        assertThat(event.action()).isEqualTo(AuditActionCatalog.BULK_EXPORT_COMPLETED);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.failureReason()).isEqualTo(job.getFailureReason());
        assertThat(event.actorType()).isEqualTo(AuditActorType.SYSTEM);
        assertTempDirEmpty();
    }

    @Test
    void process_permissionRevoked_failsWithoutReadingAnything() {
        job = job(MetadataFormat.xlsx, List.of(UUID.randomUUID()));
        stubLock();
        when(rolePermissionRepository.findEffectivePermissionCodes(userId, tenantId))
                .thenReturn(List.of(PermissionCodes.DOCM_DOCUMENTS_VIEW));
        when(keycloakUserService.getPlatformRealmRoles(keycloakId)).thenReturn(List.of("MEMBER"));

        worker.process(claimed());

        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.failed);
        assertThat(job.getFailureReason()).isEqualTo(BulkExportWorker.REASON_PERMISSION_REVOKED);
        verifyNoInteractions(query, documentService);
        assertThat(tenantContextIsClear()).isTrue();
    }

    @Test
    void process_keycloakLookupFails_rethrowsForRetryAndLeavesTheJobProcessing() {
        job = job(MetadataFormat.xlsx, List.of(UUID.randomUUID()));
        stubLock();
        when(rolePermissionRepository.findEffectivePermissionCodes(userId, tenantId)).thenReturn(List.of());
        when(keycloakUserService.getPlatformRealmRoles(keycloakId))
                .thenThrow(new KeycloakIntegrationException("Keycloak unreachable"));

        assertThatThrownBy(() -> worker.process(claimed())).isInstanceOf(KeycloakIntegrationException.class);

        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.processing);
        assertThat(job.getFailureReason()).isNull();
        verifyNoInteractions(query, documentService, outboxEventPublisher);
        assertThat(tenantContextIsClear()).isTrue();
    }

    @Test
    void process_requesterDeleted_failsTheJob() {
        job = job(MetadataFormat.xlsx, List.of(UUID.randomUUID()));
        stubLock();
        when(userRepository.findByUserIdAndTenantIdAndDeletedFalse(userId, tenantId)).thenReturn(Optional.empty());

        worker.process(claimed());

        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.failed);
        assertThat(job.getFailureReason()).isEqualTo(BulkExportWorker.REASON_REQUESTER_GONE);
        verifyNoInteractions(query, documentService);
    }

    @Test
    void process_uploadFails_failsTheJobAndStillDeletesTheTempFiles() throws Exception {
        ExportableDocumentRow doc = row("a.pdf", "clean");
        job = job(MetadataFormat.xlsx, List.of(doc.documentId()));
        stubLock();
        when(query.loadForExport(any())).thenReturn(List.of(doc));
        stubContent(doc, "a");
        when(storage.upload(any(), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("bucket unavailable"));

        worker.process(claimed());

        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.failed);
        assertThat(job.getFailureReason()).isEqualTo(BulkExportWorker.REASON_BUILD_FAILED);
        assertTempDirEmpty();
    }

    @Test
    void process_jobFailedMeanwhile_discardsTheNewFileOnRollback() {
        ExportableDocumentRow doc = row("a.pdf", "clean");
        job = job(MetadataFormat.xlsx, List.of(doc.documentId()));
        stubLock();
        when(query.loadForExport(any())).thenReturn(List.of(doc));
        when(documentService.downloadDocument(doc.documentId())).thenAnswer(invocation -> {
            // The cleanup job fails the export while it is being built.
            job.setStatus(ExportJobStatus.failed);
            return content("a");
        });

        worker.process(claimed());

        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.failed);
        verifyNoInteractions(outboxEventPublisher);
        verify(storage, never()).delete(any(), anyString());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(storage).delete(staging, "exports/" + tenantId + "/" + job.getExportJobId() + ".zip");
    }

    @Test
    void process_heartbeat_atEachStageAndEvery50Documents() {
        List<ExportableDocumentRow> rows = IntStream.range(0, 120).mapToObj(i -> row("doc" + i + ".txt", "clean"))
                .toList();
        job = job(MetadataFormat.csv, rows.stream().map(ExportableDocumentRow::documentId).toList());
        stubLock();
        when(query.loadForExport(any())).thenReturn(rows);
        rows.forEach(row -> stubContent(row, "x"));

        worker.process(claimed());

        // After the permission check, at documents 50 and 100, before the
        // metadata, before the upload.
        verify(exportJobRepository, times(5)).touch(eq(job.getExportJobId()), any());
        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.completed);
        assertThat(job.getDocumentCount()).isEqualTo(120);
    }

    // ---- helpers

    private ExportJob job(MetadataFormat format, List<UUID> documentIds) {
        BulkExportRequestParams params = new BulkExportRequestParams(BulkExportRequestParams.MODE_DOCUMENTS,
                Map.of(), List.of(new BulkExportRequestParams.SelectionLine("Selection", "picked")), documentIds, 0);
        return ExportJob.builder()
                .exportJobId(UUID.randomUUID())
                .tenantId(tenantId)
                .exportType(ExportJob.ExportType.bulk_selection)
                .metadataFormat(format)
                .requestParams(params.toJson(objectMapper))
                .status(ExportJobStatus.processing)
                .documentCount(documentIds.size())
                .correlationId(UUID.randomUUID())
                .createdBy(userId)
                .createdAt(OffsetDateTime.now().minusMinutes(1))
                .build();
    }

    private void stubLock() {
        when(exportJobRepository.lockByExportJobId(job.getExportJobId())).thenReturn(Optional.of(job));
    }

    private BulkExportWorker.ClaimedExport claimed() {
        return new BulkExportWorker.ClaimedExport(job.getExportJobId(), tenantId, userId, job.getMetadataFormat(),
                job.getRequestParams(), job.getCreatedAt());
    }

    private ExportableDocumentRow row(String filename, String scanStatus) {
        return new ExportableDocumentRow(UUID.randomUUID(), tenantId, filename, UUID.randomUUID(), "Invoice",
                userId, "Asha Perera", OffsetDateTime.now(), false, UUID.randomUUID(), 1, filename, 3L, scanStatus,
                false);
    }

    private ExportableDocumentRow deletedRow(String filename) {
        return new ExportableDocumentRow(UUID.randomUUID(), tenantId, filename, UUID.randomUUID(), "Invoice",
                userId, "Asha Perera", OffsetDateTime.now(), true, UUID.randomUUID(), 1, filename, 3L, "clean",
                false);
    }

    private void stubContent(ExportableDocumentRow row, String text) {
        when(documentService.downloadDocument(row.documentId())).thenAnswer(invocation -> content(text));
    }

    private static DownloadedDocument content(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new DownloadedDocument(new ByteArrayInputStream(bytes), bytes.length, "text/plain", "x");
    }

    private static InputStream failingStream() {
        return new InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                if (served++ < 4) {
                    return 'x';
                }
                throw new IOException("connection reset");
            }
        };
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private void assertTempDirEmpty() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files).isEmpty();
        }
    }

    private static boolean tenantContextIsClear() {
        try {
            TenantContext.getTenantId();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }
}
