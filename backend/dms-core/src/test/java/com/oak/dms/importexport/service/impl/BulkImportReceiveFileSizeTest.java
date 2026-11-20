package com.oak.dms.importexport.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.matching.ImportFilenameMatcher;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.service.BulkImportAuditor;
import com.oak.dms.importexport.service.ImportTemplateService;
import com.oak.dms.folder.FolderService;
import com.oak.dms.importexport.storage.IntakeVirusScanner;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.importexport.template.ImportTemplateCsv;
import com.oak.dms.importexport.template.ImportTemplateWorkbook;
import com.oak.dms.importexport.validation.ImportRowValidator;
import com.oak.dms.importexport.validation.ImportSchemaFields;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.scanning.exception.FileTypeNotAllowedException;
import com.oak.dms.scanning.helper.FileTypeInspector;
import com.oak.dms.scanning.helper.IngestionProperties;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.exception.StorageUploadException;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DOC-10: {@code receiveFile} judges a file by the bytes that actually arrive, not by the length its caller declared
 * (for a ZIP entry, a field the uploader wrote). Storage is stubbed here - what each adapter does with a mismatched
 * length is simulated by the stub, so these tests prove the service's own checks, not the adapters; the real-storage
 * behaviour is in {@code BulkImportSizeEnforcementIT}.
 */
class BulkImportReceiveFileSizeTest {

    private static final byte[] PDF_HEAD = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private final ImportBatchRepository importBatchRepository = mock(ImportBatchRepository.class);
    private final ImportItemRepository importItemRepository = mock(ImportItemRepository.class);
    private final ObjectStorageServiceFactory objectStorageServiceFactory = mock(ObjectStorageServiceFactory.class);
    private final StorageLocationResolver storageLocationResolver = mock(StorageLocationResolver.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final IntakeVirusScanner intakeScanner = mock(IntakeVirusScanner.class);
    private final org.springframework.transaction.PlatformTransactionManager transactionManager =
            mock(org.springframework.transaction.PlatformTransactionManager.class);
    private final ImportExportProperties properties = new ImportExportProperties();

    private ImportBatch batch;
    private final List<ImportItem> items = new ArrayList<>();
    private StorageLocation staging;

    @BeforeEach
    void setUp() {
        TenantContext.set(tenantId, userId);
        batch = ImportBatch.builder().importBatchId(UUID.randomUUID()).tenantId(tenantId)
                .sourceType(ImportBatch.SourceType.bulk_import).documentTypeId(UUID.randomUUID()).schemaVersion(1)
                .status(ImportBatchStatus.uploading).totalRecords(3).createdBy(userId)
                .createdAt(OffsetDateTime.now().minusMinutes(5)).build();
        when(importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(batch.getImportBatchId(), tenantId,
                userId)).thenReturn(Optional.of(batch));
        when(importItemRepository.findAllByBatchAndFilename(eq(batch.getImportBatchId()), any()))
                .thenAnswer(call -> items.stream()
                        .filter(i -> i.getStagedObjectKey() == null)
                        .filter(i -> i.getFilenameReference().equalsIgnoreCase(call.getArgument(1))).toList());
        // the batch total is the sum of what the rows record, as the real query does
        when(transactionManager.getTransaction(any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        when(importItemRepository.findById(any())).thenAnswer(call -> items.stream()
                .filter(i -> i.getImportItemId().equals(call.getArgument(0))).findFirst());
        when(importItemRepository.sumStagedBytes(batch.getImportBatchId())).thenAnswer(call -> items.stream()
                .mapToLong(i -> i.getStagedSizeBytes() == null ? 0 : i.getStagedSizeBytes()).sum());
        staging = StorageLocation.builder().storageLocationId(UUID.randomUUID()).purpose(StoragePurpose.staging).build();
        when(storageLocationResolver.resolveForTenant(tenantId, StoragePurpose.staging)).thenReturn(staging);
        when(objectStorageServiceFactory.resolve(staging)).thenReturn(storage);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private ImportItem row(String filename) {
        ImportItem item = ImportItem.builder().importItemId(UUID.randomUUID()).tenantId(tenantId)
                .importBatchId(batch.getImportBatchId()).rowNumber(items.size() + 2).filenameReference(filename)
                .status(ImportItem.ImportItemStatus.pending).build();
        items.add(item);
        return item;
    }

    private static byte[] pdf(int totalBytes) {
        byte[] bytes = new byte[Math.max(totalBytes, PDF_HEAD.length)];
        System.arraycopy(PDF_HEAD, 0, bytes, 0, PDF_HEAD.length);
        return bytes;
    }

    /** Storage as the file-system adapter behaves: reads to the end of the stream, whatever the length. */
    private void storageReadsToTheEnd() {
        when(storage.upload(eq(staging), any(), any(), anyLong(), any())).thenAnswer(call -> {
            long read = ((InputStream) call.getArgument(2)).transferTo(OutputStream.nullOutputStream());
            return new StoredObjectMetadata(call.getArgument(1), read, "application/pdf", null, null);
        });
    }

    /** Storage as an S3 client behaves: reads exactly contentLength bytes and never asks for more; echoes the length. */
    private void storageReadsOnlyTheDeclaredLength() {
        when(storage.upload(eq(staging), any(), any(), anyLong(), any())).thenAnswer(call -> {
            long declared = call.getArgument(3);
            ((InputStream) call.getArgument(2)).readNBytes((int) declared);
            return new StoredObjectMetadata(call.getArgument(1), declared, "application/pdf", null, null);
        });
    }

    private void receive(String filename, long declared, byte[] actual) {
        service().receiveFile(batch.getImportBatchId(), filename, "application/pdf", declared,
                new ByteArrayInputStream(actual));
    }

    private BulkImportServiceImpl service() {
        return new BulkImportServiceImpl(importBatchRepository, importItemRepository,
                mock(DocumentTypeRepository.class), mock(DocumentTypeFieldService.class), new ImportTemplateWorkbook(),
                new ImportFilenameMatcher(), storageLocationResolver, objectStorageServiceFactory,
                new StagedImportFiles(mock(StorageLocationRepository.class), objectStorageServiceFactory),
                mock(OutboxEventPublisher.class), properties, new ObjectMapper(),
                new FileTypeInspector(new IngestionProperties()), mock(ImportSchemaFields.class),
                mock(BulkImportAuditor.class), new ImportTemplateCsv(), mock(ImportRowValidator.class),
                mock(ImportTemplateService.class), mock(FolderService.class), intakeScanner, transactionManager);
    }

    private void assertNothingStaged(ImportItem item) {
        assertThat(item.getStagedObjectKey()).isNull();
        assertThat(item.getStagedSizeBytes()).isNull();
        verify(importItemRepository, never()).save(item);
    }

    // ------------------------------------------------------------------------------------------------ declared vs actual

    @Test
    void actualEqualsDeclared_isStaged_andTheVerifiedSizeIsRecorded() {
        ImportItem item = row("a.pdf");
        storageReadsToTheEnd();
        byte[] content = pdf(5000);

        receive("a.pdf", content.length, content);

        assertThat(item.getStagedSizeBytes()).isEqualTo(5000L);
        assertThat(item.getStagedObjectKey()).isNotNull();
        verify(importItemRepository).save(item);
        verify(intakeScanner).scan(eq(tenantId), eq(batch.getImportBatchId()), eq(item.getImportItemId()), eq("a.pdf"),
                eq(staging), any(), eq(5000L), eq("application/pdf"));
    }

    @Test
    void actualLargerThanDeclared_isRejected_theObjectRemoved_andNothingIsRecorded() {
        ImportItem item = row("a.pdf");
        storageReadsToTheEnd();                                  // the file-system adapter: stores whatever it is given
        byte[] content = pdf(8 * 1024 * 1024);

        assertThatThrownBy(() -> receive("a.pdf", 100, content)).isInstanceOf(BulkImportException.class)
                .extracting("code").isEqualTo("IMPORT_FILE_SIZE_MISMATCH");

        assertNothingStaged(item);
        verify(storage).delete(eq(staging), any());
        verifyNoInteractions(intakeScanner);
    }

    @Test
    void actualLargerThanDeclared_isCaught_evenWhenTheAdapterNeverReadsPastTheDeclaredLength() {
        ImportItem item = row("a.pdf");
        storageReadsOnlyTheDeclaredLength();                     // an S3-style client
        byte[] content = pdf(50_000);

        assertThatThrownBy(() -> receive("a.pdf", 1000, content)).isInstanceOf(BulkImportException.class)
                .extracting("code").isEqualTo("IMPORT_FILE_SIZE_MISMATCH");

        assertNothingStaged(item);
        verify(storage).delete(eq(staging), any());
    }

    @Test
    void actualShorterThanDeclared_isRejected_andTheObjectRemoved() {
        ImportItem item = row("a.pdf");
        storageReadsToTheEnd();
        byte[] content = pdf(300);

        assertThatThrownBy(() -> receive("a.pdf", 5000, content)).isInstanceOf(BulkImportException.class)
                .extracting("code").isEqualTo("IMPORT_FILE_SIZE_MISMATCH");

        assertNothingStaged(item);
        verify(storage).delete(eq(staging), any());
    }

    @Test
    void aShortStreamThatMakesStorageFail_isReportedAsASizeMismatch_notAStorageFailure() {
        ImportItem item = row("a.pdf");
        when(storage.upload(eq(staging), any(), any(), anyLong(), any())).thenAnswer(call -> {
            ((InputStream) call.getArgument(2)).readAllBytes();
            throw new StorageUploadException("k", new java.io.IOException("Content-Length mismatch"));
        });

        assertThatThrownBy(() -> receive("a.pdf", 5000, pdf(300))).isInstanceOf(BulkImportException.class)
                .extracting("code").isEqualTo("IMPORT_FILE_SIZE_MISMATCH");

        assertNothingStaged(item);
        verify(storage).delete(eq(staging), any());
    }

    @Test
    void anAdapterThatStoresLessThanItWasGiven_isRejected() {
        ImportItem item = row("a.pdf");
        when(storage.upload(eq(staging), any(), any(), anyLong(), any())).thenAnswer(call -> {
            ((InputStream) call.getArgument(2)).readAllBytes();
            return new StoredObjectMetadata("k", 10, "application/pdf", null, null);   // reports a different size
        });

        assertThatThrownBy(() -> receive("a.pdf", 500, pdf(500))).isInstanceOf(BulkImportException.class)
                .extracting("code").isEqualTo("IMPORT_FILE_SIZE_MISMATCH");

        assertNothingStaged(item);
    }

    @Test
    void anAdapterThatStopsReadingBeforeTheDeclaredLength_leavesNoAcceptedTruncatedObject() {
        ImportItem item = row("a.pdf");
        when(storage.upload(eq(staging), any(), any(), anyLong(), any())).thenAnswer(call -> {
            ((InputStream) call.getArgument(2)).readNBytes(100);        // stops early
            return new StoredObjectMetadata("k", 500, "application/pdf", null, null);
        });

        assertThatThrownBy(() -> receive("a.pdf", 500, pdf(500))).isInstanceOf(BulkImportException.class)
                .extracting("code").isEqualTo("IMPORT_FILE_SIZE_MISMATCH");

        assertNothingStaged(item);
        verify(storage).delete(eq(staging), any());
    }

    // ------------------------------------------------------------------------------------------------ the batch budget

    @Test
    void declaredLargerThanTheRemainingBudget_isRefusedBeforeStorageIsTouched() {
        properties.setMaxBatchSize(DataSize.ofBytes(1000));
        ImportItem item = row("a.pdf");
        ImportItem done = row("done.pdf");
        done.setStagedObjectKey("k");
        done.setStagedSizeBytes(990L);

        assertThatThrownBy(() -> receive("a.pdf", 11, pdf(11))).isInstanceOf(ImportBatchSizeException.class)
                .extracting("code").isEqualTo("IMPORT_BATCH_SIZE_EXCEEDED");

        assertNothingStaged(item);
        verifyNoInteractions(storage);
    }

    @Test
    void declaredExactlyTheRemainingBudget_isAccepted() {
        properties.setMaxBatchSize(DataSize.ofBytes(1000));
        ImportItem item = row("a.pdf");
        ImportItem done = row("done.pdf");
        done.setStagedObjectKey("k");
        done.setStagedSizeBytes(990L);
        storageReadsToTheEnd();

        receive("a.pdf", 10, pdf(10));

        assertThat(item.getStagedSizeBytes()).isEqualTo(10L);
    }

    @Test
    void aForgedHugeDeclaredLength_cannotOverflowTheBudgetCheck() {
        ImportItem item = row("a.pdf");
        ImportItem done = row("done.pdf");
        done.setStagedObjectKey("k");
        done.setStagedSizeBytes(5000L);

        assertThatThrownBy(() -> receive("a.pdf", Long.MAX_VALUE - 100, pdf(20)))
                .isInstanceOf(ImportBatchSizeException.class);

        assertNothingStaged(item);
        verifyNoInteractions(storage);
    }

    @Test
    void manyFilesTogether_consumeTheBudgetByTheirRealSize_andTheOneThatDoesNotFitIsRefused() {
        properties.setMaxBatchSize(DataSize.ofBytes(150));
        ImportItem first = row("1.pdf");
        ImportItem second = row("2.pdf");
        ImportItem third = row("3.pdf");
        storageReadsToTheEnd();

        receive("1.pdf", 60, pdf(60));
        receive("2.pdf", 60, pdf(60));
        assertThatThrownBy(() -> receive("3.pdf", 60, pdf(60))).isInstanceOf(ImportBatchSizeException.class);
        receive("3.pdf", 30, pdf(30));                                   // what is left still fits

        assertThat(first.getStagedSizeBytes()).isEqualTo(60L);
        assertThat(second.getStagedSizeBytes()).isEqualTo(60L);
        assertThat(third.getStagedSizeBytes()).isEqualTo(30L);
    }

    @Test
    void aForgedSmallDeclaredLength_cannotSlipAFileThroughOnceTheBudgetIsUsed() {
        properties.setMaxBatchSize(DataSize.ofBytes(100));
        row("big.pdf");
        row("small.pdf");
        storageReadsToTheEnd();

        assertThatThrownBy(() -> receive("big.pdf", 20, pdf(8 * 1024 * 1024))).isInstanceOf(BulkImportException.class);

        // nothing was recorded for it, so the budget is untouched and an honest file is judged against the true total
        receive("small.pdf", 90, pdf(90));
        assertThat(items.get(1).getStagedSizeBytes()).isEqualTo(90L);
        assertThat(items.get(0).getStagedSizeBytes()).isNull();
    }

    // ------------------------------------------------------------------------------------------------ unknown sizes

    @Test
    void aNegativeDeclaredLength_isRefused_andNothingIsTouched() {
        ImportItem item = row("a.pdf");

        assertThatThrownBy(() -> receive("a.pdf", -1, pdf(100))).isInstanceOf(BulkImportException.class)
                .extracting("code").isEqualTo("IMPORT_FILE_SIZE_UNKNOWN");

        assertNothingStaged(item);
        verifyNoInteractions(storage);
    }

    @Test
    void aZeroLengthFile_isStillJudgedByTheTypeCheckAsBefore() {
        ImportItem item = row("a.pdf");

        assertThatThrownBy(() -> receive("a.pdf", 0, new byte[0])).isInstanceOf(FileTypeNotAllowedException.class);

        assertNothingStaged(item);
        verifyNoInteractions(storage);
    }

    // ------------------------------------------------------------------------------------------------ failures and cleanup

    @Test
    void aStorageFailureMidStream_removesWhatWasWritten_andLeavesTheRowAsItWas() {
        ImportItem item = row("a.pdf");
        when(storage.upload(eq(staging), any(), any(), anyLong(), any())).thenAnswer(call -> {
            ((InputStream) call.getArgument(2)).readNBytes(50);          // part of the file is written ...
            throw new StorageUploadException("k", new java.io.IOException("disk full"));   // ... then it fails
        });

        assertThatThrownBy(() -> receive("a.pdf", 500, pdf(500))).isInstanceOf(StorageUploadException.class);

        assertNothingStaged(item);
        verify(storage).delete(eq(staging), any());
    }

    @Test
    void aCleanupThatFails_isNotSwallowed_itIsAttachedToTheException() {
        row("a.pdf");
        storageReadsToTheEnd();
        doThrow(new IllegalStateException("cannot delete")).when(storage).delete(eq(staging), any());

        assertThatThrownBy(() -> receive("a.pdf", 10, pdf(500))).isInstanceOf(BulkImportException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1)
                        .allSatisfy(s -> assertThat(s).hasMessage("cannot delete")));
    }

    @Test
    void aRejectedFile_leavesWhatWasAcceptedEarlierExactlyAsItWas() {
        ImportItem accepted = row("ok.pdf");
        ImportItem rejected = row("bad.pdf");
        storageReadsToTheEnd();
        receive("ok.pdf", 400, pdf(400));
        String keyBefore = accepted.getStagedObjectKey();
        long sizeBefore = accepted.getStagedSizeBytes();
        String hashBefore = accepted.getStagedFileHash();

        assertThatThrownBy(() -> receive("bad.pdf", 100, pdf(9000))).isInstanceOf(BulkImportException.class);

        assertThat(accepted.getStagedObjectKey()).isEqualTo(keyBefore);
        assertThat(accepted.getStagedSizeBytes()).isEqualTo(sizeBefore);
        assertThat(accepted.getStagedFileHash()).isEqualTo(hashBefore);
        assertNothingStaged(rejected);
        // only the rejected row's own object key was deleted
        verify(storage).delete(eq(staging), org.mockito.ArgumentMatchers.startsWith("imports/" + tenantId + "/"
                + batch.getImportBatchId() + "/" + rejected.getImportItemId() + "/"));
        verify(storage, org.mockito.Mockito.times(1)).delete(eq(staging), any());
    }
}
