package com.oak.dms.importexport.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.exception.ImportBatchStateException;
import com.oak.dms.importexport.exception.ImportFileAlreadyReceivedException;
import com.oak.dms.importexport.exception.ImportFileBlockedException;
import com.oak.dms.importexport.exception.ImportFileNotInBatchException;
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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DOC-11: {@code receiveFile} is a short transaction, then storage and scan work with none, then a short transaction that
 * decides again. The transaction manager and storage are stubbed and every step writes an event to one list, so these
 * tests prove the ORDER and the decisions - that nothing external runs between a begin and its commit, which races are
 * refused and cleaned up, and what happens when a commit's outcome is in doubt. That no real transaction or connection
 * is held during the I/O is proven against PostgreSQL in {@code BulkImportConcurrencyIT}.
 */
class BulkImportReceiveFilePhasesTest {

    private static final byte[] PDF_HEAD = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private final ImportBatchRepository importBatchRepository = mock(ImportBatchRepository.class);
    private final ImportItemRepository importItemRepository = mock(ImportItemRepository.class);
    private final ObjectStorageServiceFactory objectStorageServiceFactory = mock(ObjectStorageServiceFactory.class);
    private final StorageLocationResolver storageLocationResolver = mock(StorageLocationResolver.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final IntakeVirusScanner intakeScanner = mock(IntakeVirusScanner.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ImportExportProperties properties = new ImportExportProperties();

    private final List<String> events = new ArrayList<>();
    private ImportBatch batch;
    private ImportItem item;
    private ImportItem other;
    private StorageLocation staging;

    @BeforeEach
    void setUp() {
        TenantContext.set(tenantId, userId);
        batch = ImportBatch.builder().importBatchId(UUID.randomUUID()).tenantId(tenantId)
                .sourceType(ImportBatch.SourceType.bulk_import).documentTypeId(UUID.randomUUID()).schemaVersion(1)
                .status(ImportBatchStatus.uploading).totalRecords(2).createdBy(userId)
                .createdAt(OffsetDateTime.now().minusMinutes(5)).build();
        item = row("a.pdf", 2);
        other = row("b.pdf", 3);
        when(importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(batch.getImportBatchId(), tenantId,
                userId)).thenReturn(Optional.of(batch));
        when(importItemRepository.findAllByBatchAndFilename(eq(batch.getImportBatchId()), any()))
                .thenAnswer(call -> List.of(item, other).stream()
                        .filter(i -> i.getStagedObjectKey() == null)
                        .filter(i -> i.getFilenameReference().equalsIgnoreCase(call.getArgument(1))).toList());
        when(importItemRepository.findById(item.getImportItemId())).thenAnswer(call -> Optional.of(item));
        when(importItemRepository.sumStagedBytes(batch.getImportBatchId())).thenAnswer(call -> List.of(item, other)
                .stream().mapToLong(i -> i.getStagedSizeBytes() == null ? 0 : i.getStagedSizeBytes()).sum());
        when(importItemRepository.countReceived(batch.getImportBatchId())).thenAnswer(call -> List.of(item, other)
                .stream().filter(i -> i.getStagedObjectKey() != null).count());
        staging = StorageLocation.builder().storageLocationId(UUID.randomUUID()).purpose(StoragePurpose.staging).build();
        when(storageLocationResolver.resolveForTenant(tenantId, StoragePurpose.staging)).thenReturn(staging);
        when(objectStorageServiceFactory.resolve(staging)).thenReturn(storage);
        // every step leaves an event, so the order can be asserted
        when(transactionManager.getTransaction(any())).thenAnswer(call -> {
            events.add("begin");
            return new SimpleTransactionStatus();
        });
        doAnswer(call -> {
            events.add("commit");
            return null;
        }).when(transactionManager).commit(any());
        doAnswer(call -> {
            events.add("rollback");
            return null;
        }).when(transactionManager).rollback(any());
        when(storage.upload(eq(staging), any(), any(), anyLong(), any())).thenAnswer(call -> {
            events.add("upload");
            long read = ((InputStream) call.getArgument(2)).transferTo(OutputStream.nullOutputStream());
            return new StoredObjectMetadata(call.getArgument(1), read, "application/pdf", null, null);
        });
        doAnswer(call -> {
            events.add("scan");
            return null;
        }).when(intakeScanner).scan(any(), any(), any(), any(), any(), any(), anyLong(), any());
        doAnswer(call -> {
            events.add("delete");
            return null;
        }).when(storage).delete(eq(staging), any());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    // ------------------------------------------------------------------------------------------------ transactional advice

    @Test
    void receiveFile_carriesNoTransactionalAdviceAtAll_whileTheMethodsThatWriteStillDo() throws Exception {
        // Not even NEVER or NOT_SUPPORTED: they create an empty transaction whose synchronization binds an EntityManager -
        // and with it a pooled connection - to the thread for the whole method (found by BulkImportConcurrencyIT).
        assertThat(BulkImportServiceImpl.class.isAnnotationPresent(
                org.springframework.transaction.annotation.Transactional.class)).isFalse();
        var receiveFile = BulkImportServiceImpl.class.getMethod("receiveFile", UUID.class, String.class, String.class,
                long.class, InputStream.class);
        assertThat(receiveFile.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
                .isFalse();
        for (String writer : List.of("submit", "cancel")) {
            var method = BulkImportServiceImpl.class.getMethod(writer, UUID.class);
            assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                    .as(writer + " writes, so it is transactional").isNotNull();
        }
        var createBatch = BulkImportServiceImpl.class.getMethod("createBatch", String.class, long.class,
                InputStream.class, UUID.class, String.class, List.class);
        assertThat(createBatch.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNotNull();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private ImportItem row(String filename, int rowNumber) {
        return ImportItem.builder().importItemId(UUID.randomUUID()).tenantId(tenantId)
                .importBatchId(batch.getImportBatchId()).rowNumber(rowNumber).filenameReference(filename)
                .status(ImportItem.ImportItemStatus.pending).build();
    }

    private static byte[] pdf(int totalBytes) {
        byte[] bytes = new byte[Math.max(totalBytes, PDF_HEAD.length)];
        System.arraycopy(PDF_HEAD, 0, bytes, 0, PDF_HEAD.length);
        return bytes;
    }

    private ImportFileReceivedResponse receive(long declared, byte[] actual) {
        return service().receiveFile(batch.getImportBatchId(), "a.pdf", "application/pdf", declared,
                new ByteArrayInputStream(actual));
    }

    private ImportFileReceivedResponse receive(int size) {
        return receive(size, pdf(size));
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

    private String uploadedKey() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storage).upload(eq(staging), key.capture(), any(), anyLong(), any());
        return key.getValue();
    }

    private void assertNotStaged() {
        assertThat(item.getStagedObjectKey()).isNull();
        assertThat(item.getStagedSizeBytes()).isNull();
        verify(importItemRepository, never()).save(item);
    }

    // ------------------------------------------------------------------------------------------------ the happy path

    @Test
    void theThreePhases_runInOrder_andNothingExternalHappensBetweenABeginAndItsCommit() {
        ImportFileReceivedResponse response = receive(500);

        assertThat(events).containsExactly("begin", "commit", "upload", "scan", "begin", "commit");
        assertThat(item.getStagedSizeBytes()).as("the verified size").isEqualTo(500L);
        assertThat(item.getStagedObjectKey()).isEqualTo(uploadedKey());
        assertThat(response.rowNumber()).isEqualTo(2);
        assertThat(response.filesReceived()).isEqualTo(1);
        assertThat(response.totalRecords()).isEqualTo(2);
        verify(importItemRepository).save(item);
    }

    @Test
    void everyAttemptGetsItsOwnObjectKey() {
        receive(300);
        String first = item.getStagedObjectKey();
        item.setStagedObjectKey(null);       // as if the first one had been refused and the row is free again
        item.setStagedSizeBytes(null);
        receive(300);

        assertThat(item.getStagedObjectKey()).isNotEqualTo(first);
        assertThat(first).startsWith("imports/" + tenantId + "/" + batch.getImportBatchId() + "/" + item.getImportItemId() + "/");
    }

    // ------------------------------------------------------------------------------------------------ preliminary refusals

    @Test
    void aBatchThatNoLongerAcceptsUploads_isRefusedInTheFirstTransaction_withNoIO() {
        batch.setStatus(ImportBatchStatus.queued);

        assertThatThrownBy(() -> receive(100)).isInstanceOf(ImportBatchStateException.class);

        assertThat(events).containsExactly("begin", "rollback");
        verifyNoInteractions(storage, intakeScanner);
        assertNotStaged();
    }

    @Test
    void aFileNotInTheTemplate_isRefusedInTheFirstTransaction_withNoIO() {
        assertThatThrownBy(() -> service().receiveFile(batch.getImportBatchId(), "unknown.pdf", "application/pdf", 100,
                new ByteArrayInputStream(pdf(100)))).isInstanceOf(ImportFileNotInBatchException.class);

        assertThat(events).containsExactly("begin", "rollback");
        verifyNoInteractions(storage, intakeScanner);
    }

    @Test
    void aDeclaredSizeThatCannotFit_isRefusedBeforeAnyIO() {
        properties.setMaxBatchSize(org.springframework.util.unit.DataSize.ofBytes(100));

        assertThatThrownBy(() -> receive(101)).isInstanceOf(ImportBatchSizeException.class);

        assertThat(events).containsExactly("begin", "rollback");
        verifyNoInteractions(storage, intakeScanner);
    }

    @Test
    void aCallerAlreadyInsideATransaction_isRefusedBeforeAnythingHappens() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> receive(100)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not run inside a transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        assertThat(events).isEmpty();
        verifyNoInteractions(storage, intakeScanner);
    }

    // ------------------------------------------------------------------------------------------------ races between phases

    @Test
    void cancelledWhileTheFileWasBeingStored_isRefused_andTheObjectRemoved() {
        doAnswer(call -> {
            events.add("scan");
            batch.setStatus(ImportBatchStatus.cancelled);       // cancel commits during phase 2
            return null;
        }).when(intakeScanner).scan(any(), any(), any(), any(), any(), any(), anyLong(), any());

        assertThatThrownBy(() -> receive(400)).isInstanceOf(ImportBatchStateException.class);

        assertThat(events).containsExactly("begin", "commit", "upload", "scan", "begin", "rollback", "delete");
        String attemptKey = uploadedKey();
        verify(storage).delete(staging, attemptKey);
        assertNotStaged();
    }

    @Test
    void submittedWhileTheFileWasBeingStored_isRefused_andTheObjectRemoved() {
        doAnswer(call -> {
            events.add("scan");
            batch.setStatus(ImportBatchStatus.queued);          // submit commits during phase 2
            return null;
        }).when(intakeScanner).scan(any(), any(), any(), any(), any(), any(), anyLong(), any());

        assertThatThrownBy(() -> receive(400)).isInstanceOf(ImportBatchStateException.class);

        assertThat(events).containsExactly("begin", "commit", "upload", "scan", "begin", "rollback", "delete");
        assertNotStaged();
    }

    @Test
    void anotherUploadTookTheRemainingCapacityMeanwhile_isRefused_andTheObjectRemoved() {
        properties.setMaxBatchSize(org.springframework.util.unit.DataSize.ofBytes(1000));
        doAnswer(call -> {
            events.add("scan");
            other.setStagedObjectKey("someone-elses-object");    // a concurrent upload committed 700 bytes
            other.setStagedSizeBytes(700L);
            return null;
        }).when(intakeScanner).scan(any(), any(), any(), any(), any(), any(), anyLong(), any());

        assertThatThrownBy(() -> receive(400)).isInstanceOf(ImportBatchSizeException.class)
                .extracting("code").isEqualTo("IMPORT_BATCH_SIZE_EXCEEDED");

        String attemptKey = uploadedKey();
        verify(storage).delete(staging, attemptKey);
        verify(storage, never()).delete(staging, "someone-elses-object");
        assertNotStaged();
        assertThat(other.getStagedSizeBytes()).as("the other upload's total is untouched").isEqualTo(700L);
    }

    @Test
    void anotherUploadTookTheSameRowMeanwhile_isRefused_andOnlyThisAttemptsObjectIsRemoved() {
        doAnswer(call -> {
            events.add("scan");
            item.setStagedObjectKey("the-winners-object");       // a concurrent upload for the same row committed first
            item.setStagedSizeBytes(400L);
            return null;
        }).when(intakeScanner).scan(any(), any(), any(), any(), any(), any(), anyLong(), any());

        assertThatThrownBy(() -> receive(400)).isInstanceOf(ImportFileAlreadyReceivedException.class);

        String attemptKey = uploadedKey();
        verify(storage).delete(staging, attemptKey);
        verify(storage, never()).delete(staging, "the-winners-object");
        assertThat(item.getStagedObjectKey()).isEqualTo("the-winners-object");
        verify(importItemRepository, never()).save(item);
    }

    @Test
    void theBudgetIsCheckedAtTheEnd_againstTheRealSize_exactBoundaryAccepted() {
        properties.setMaxBatchSize(org.springframework.util.unit.DataSize.ofBytes(1000));
        other.setStagedObjectKey("k");
        other.setStagedSizeBytes(600L);

        receive(400);

        assertThat(item.getStagedSizeBytes()).isEqualTo(400L);
    }

    // ------------------------------------------------------------------------------------------------ failures in phase 2

    @Test
    void aStorageFailure_removesWhatWasWritten_andNeverOpensTheLastTransaction() {
        when(storage.upload(eq(staging), any(), any(), anyLong(), any())).thenAnswer(call -> {
            events.add("upload");
            ((InputStream) call.getArgument(2)).readNBytes(40);
            throw new StorageUploadException("k", new java.io.IOException("disk full"));
        });

        assertThatThrownBy(() -> receive(500)).isInstanceOf(StorageUploadException.class);

        assertThat(events).containsExactly("begin", "commit", "upload", "delete");
        assertNotStaged();
    }

    @Test
    void infectedContent_isRefused_theObjectRemoved_andTheLastTransactionNeverOpens() {
        doThrow(new ImportFileBlockedException("'a.pdf' was blocked by the virus scan")).when(intakeScanner)
                .scan(any(), any(), any(), any(), any(), any(), anyLong(), any());

        assertThatThrownBy(() -> receive(500)).isInstanceOf(ImportFileBlockedException.class);

        assertThat(events).containsExactly("begin", "commit", "upload", "delete");
        assertNotStaged();
    }

    @Test
    void aLengthMismatch_isRefused_theObjectRemoved_andTheLastTransactionNeverOpens() {
        assertThatThrownBy(() -> receive(100, pdf(5000))).isInstanceOf(BulkImportException.class)
                .extracting("code").isEqualTo("IMPORT_FILE_SIZE_MISMATCH");

        assertThat(events).containsExactly("begin", "commit", "upload", "delete");
        verifyNoInteractions(intakeScanner);
        assertNotStaged();
    }

    @Test
    void aCleanupThatFails_isNotSwallowed_itIsAttachedToTheError() {
        doThrow(new IllegalStateException("cannot delete")).when(storage).delete(eq(staging), any());
        doThrow(new ImportFileBlockedException("blocked")).when(intakeScanner)
                .scan(any(), any(), any(), any(), any(), any(), anyLong(), any());

        assertThatThrownBy(() -> receive(500)).isInstanceOf(ImportFileBlockedException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1)
                        .allSatisfy(s -> assertThat(s).hasMessage("cannot delete")));
    }

    // ------------------------------------------------------------------------------------------------ failures in phase 3

    @Test
    void aDatabaseFailureWhileRecording_rollsBack_andRemovesTheObject() {
        doThrow(new DataAccessResourceFailureException("save failed")).when(importItemRepository).save(item);

        assertThatThrownBy(() -> receive(500)).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(events).containsExactly("begin", "commit", "upload", "scan", "begin", "rollback", "delete");
        String attemptKey = uploadedKey();
        verify(storage).delete(staging, attemptKey);
    }

    // ------------------------------------------------------------------------------------------------ commit outcome

    @Test
    void aCommitThatReportsAnErrorButDidCommit_isASuccess_andTheObjectIsKept() {
        doNothing().doThrow(new TransactionSystemException("commit failed")).doNothing().when(transactionManager)
                .commit(any());                                      // 1st ok, 2nd (the recording) throws, 3rd (the lookup) ok

        ImportFileReceivedResponse response = receive(500);          // the row IS recorded: the lookup finds this key

        assertThat(response.rowNumber()).isEqualTo(2);
        assertThat(response.filesReceived()).isEqualTo(1);
        verify(storage, never()).delete(any(), any());
        assertThat(item.getStagedObjectKey()).isEqualTo(uploadedKey());
    }

    @Test
    void aCommitThatFailedAndLeftNoRow_removesTheObject_andRethrows() {
        doNothing().doThrow(new TransactionSystemException("commit failed")).doNothing().when(transactionManager)
                .commit(any());
        ImportItem freshFromTheDatabase = row("a.pdf", 2);           // what the lookup sees: nothing was recorded
        freshFromTheDatabase.setImportItemId(item.getImportItemId());
        when(importItemRepository.findById(item.getImportItemId())).thenReturn(Optional.of(item))
                .thenReturn(Optional.of(freshFromTheDatabase));

        assertThatThrownBy(() -> receive(500)).isInstanceOf(TransactionSystemException.class);

        String attemptKey = uploadedKey();
        verify(storage).delete(staging, attemptKey);
    }

    @Test
    void aCommitWhoseOutcomeCannotBeDetermined_keepsTheObject_andSaysSo() {
        doNothing().doThrow(new TransactionSystemException("commit failed")).doNothing().when(transactionManager)
                .commit(any());
        when(importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(batch.getImportBatchId(), tenantId, userId))
                .thenReturn(Optional.of(batch)).thenReturn(Optional.of(batch))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));   // the lookup fails

        assertThatThrownBy(() -> receive(500)).isInstanceOf(BulkImportException.class)
                .extracting("code").isEqualTo("IMPORT_FILE_STATE_UNCERTAIN");

        verify(storage, never()).delete(any(), any());      // an accepted file is never deleted on a guess
    }

    @Test
    void whenTheRecordingNeverStarted_aBeginFailureIsNotAnUncertainCommit() {
        // e.g. no connection could be obtained for the last transaction: nothing was ever attempted, so the object goes
        doAnswer(call -> {
            events.add("begin");
            if (events.stream().filter("begin"::equals).count() == 2) {
                throw new org.springframework.transaction.CannotCreateTransactionException("pool exhausted");
            }
            return new SimpleTransactionStatus();
        }).when(transactionManager).getTransaction(any());

        assertThatThrownBy(() -> receive(500))
                .isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class);

        String attemptKey = uploadedKey();
        verify(storage).delete(staging, attemptKey);
        assertNotStaged();
    }
}
