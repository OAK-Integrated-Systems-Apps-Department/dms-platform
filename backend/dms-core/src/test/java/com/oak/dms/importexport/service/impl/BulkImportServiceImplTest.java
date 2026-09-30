package com.oak.dms.importexport.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.exception.ImportBatchNotCancellableException;
import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.exception.InactiveDocumentTypeException;
import com.oak.dms.importexport.exception.InvalidImportTemplateException;
import com.oak.dms.importexport.matching.ImportFilenameMatcher;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.template.ImportTemplateWorkbook;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BulkImportServiceImplTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentTypeId = UUID.randomUUID();

    private final ImportBatchRepository importBatchRepository = mock(ImportBatchRepository.class);
    private final ImportItemRepository importItemRepository = mock(ImportItemRepository.class);
    private final DocumentTypeRepository documentTypeRepository = mock(DocumentTypeRepository.class);
    private final DocumentTypeFieldService documentTypeFieldService = mock(DocumentTypeFieldService.class);
    private final ObjectStorageServiceFactory objectStorageServiceFactory = mock(ObjectStorageServiceFactory.class);
    private final StorageLocationRepository storageLocationRepository = mock(StorageLocationRepository.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final ImportTemplateWorkbook realWorkbook = new ImportTemplateWorkbook();

    @BeforeEach
    void setTenant() {
        TenantContext.set(tenantId, userId);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void createBatch_templateForOlderSchemaVersion_rejectsAndSavesNothing() {
        byte[] template = realWorkbook.generate(schema(2), 500);
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.of(documentType(3, DocumentType.DocumentTypeStatus.active)));

        assertThatThrownBy(() -> service(realWorkbook).createBatch(template.length, new ByteArrayInputStream(template)))
                .isInstanceOf(InvalidImportTemplateException.class)
                .extracting("code").isEqualTo("IMPORT_TEMPLATE_OUTDATED");

        verify(importBatchRepository, never()).save(any());
        verifyNoInteractions(importItemRepository);
    }

    @Test
    void createBatch_typeNotInCallersTenant_rejectsAsInvalidTemplate() {
        byte[] template = realWorkbook.generate(schema(3), 500);
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(realWorkbook).createBatch(template.length, new ByteArrayInputStream(template)))
                .isInstanceOf(InvalidImportTemplateException.class)
                .extracting("code").isEqualTo("IMPORT_TEMPLATE_INVALID");
    }

    @Test
    void createBatch_templateOverSizeLimit_rejectsWithoutParsing() {
        ImportTemplateWorkbook workbook = mock(ImportTemplateWorkbook.class);
        long sixMegabytes = 6L * 1024 * 1024;

        assertThatThrownBy(() -> service(workbook).createBatch(sixMegabytes, new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(ImportBatchSizeException.class)
                .extracting("code").isEqualTo("IMPORT_TEMPLATE_TOO_LARGE");

        verifyNoInteractions(workbook);
    }

    @Test
    void createBatch_inactiveDocumentType_rejects() {
        byte[] template = realWorkbook.generate(schema(3), 500);
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.of(documentType(3, DocumentType.DocumentTypeStatus.inactive)));

        assertThatThrownBy(() -> service(realWorkbook).createBatch(template.length, new ByteArrayInputStream(template)))
                .isInstanceOf(InactiveDocumentTypeException.class);

        verify(importBatchRepository, never()).save(any());
    }

    @Test
    void generateTemplate_inactiveDocumentType_rejectsWithoutLoadingSchema() {
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.of(documentType(3, DocumentType.DocumentTypeStatus.inactive)));

        assertThatThrownBy(() -> service(realWorkbook).generateTemplate(documentTypeId))
                .isInstanceOf(InactiveDocumentTypeException.class);

        verifyNoInteractions(documentTypeFieldService);
    }

    @Test
    void listDocumentTypes_returnsActiveTypesForCallersTenantAsOptions() {
        DocumentType invoice = option("INV", "Invoice", DocumentType.DocumentTypeStatus.active);
        DocumentType retired = option("OLD", "Retired", DocumentType.DocumentTypeStatus.inactive);
        DocumentType contract = option("CON", "Contract", DocumentType.DocumentTypeStatus.active);
        when(documentTypeRepository.findAllByTenantOrderedByDisplayOrder(tenantId))
                .thenReturn(List.of(invoice, retired, contract));

        List<ImportDocumentTypeOption> options = service(realWorkbook).listDocumentTypes();

        assertThat(options).containsExactly(
                new ImportDocumentTypeOption(invoice.getDocumentTypeId(), "Invoice", "INV"),
                new ImportDocumentTypeOption(contract.getDocumentTypeId(), "Contract", "CON"));
        verify(documentTypeRepository).findAllByTenantOrderedByDisplayOrder(tenantId);
    }

    @Test
    void cancel_uploadingBatch_cancelsClearsStagedColumnsAndDeletesFilesAfterCommit() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        StorageLocation location = StorageLocation.builder().storageLocationId(UUID.randomUUID()).build();
        ImportItem staged = item(batch, 1, "a.pdf", location.getStorageLocationId(), "imports/key-1");
        ImportItem missing = item(batch, 2, "b.pdf", null, null);
        stubLockedBatch(batch, List.of(staged, missing));
        when(storageLocationRepository.findByStorageLocationIdAndDeletedFalse(location.getStorageLocationId()))
                .thenReturn(Optional.of(location));
        when(objectStorageServiceFactory.resolve(location)).thenReturn(storage);

        BulkImportStatusResponse response = inTransaction(() -> service(realWorkbook).cancel(batch.getImportBatchId()),
                () -> verifyNoInteractions(storage));

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.cancelled);
        assertThat(batch.getUpdatedBy()).isEqualTo(userId);
        assertThat(staged.getStagedObjectKey()).isNull();
        assertThat(staged.getStagedStorageLocationId()).isNull();
        assertThat(staged.getStagedSizeBytes()).isNull();
        assertThat(response.status()).isEqualTo("cancelled");
        assertThat(response.filesReceived()).isZero();
        assertThat(response.missingFilenames()).containsExactly("a.pdf", "b.pdf");
        verify(importBatchRepository).save(batch);
        verify(storage).delete(location, "imports/key-1");
    }

    @Test
    void cancel_queuedBatch_cancels() {
        ImportBatch batch = batch(ImportBatchStatus.queued);
        stubLockedBatch(batch, List.of(item(batch, 1, "a.pdf", null, null)));

        BulkImportStatusResponse response = service(realWorkbook).cancel(batch.getImportBatchId());

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.cancelled);
        assertThat(response.status()).isEqualTo("cancelled");
        verify(importBatchRepository).save(batch);
    }

    @Test
    void cancel_runningBatch_rejectsWith409AndChangesNothing() {
        ImportBatch batch = batch(ImportBatchStatus.running);
        stubLockedBatch(batch, List.of());

        assertThatThrownBy(() -> service(realWorkbook).cancel(batch.getImportBatchId()))
                .isInstanceOf(ImportBatchNotCancellableException.class)
                .extracting("code").isEqualTo("IMPORT_BATCH_NOT_CANCELLABLE");

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.running);
        verify(importBatchRepository, never()).save(any());
        verify(importItemRepository, never()).saveAll(anyList());
        verifyNoInteractions(objectStorageServiceFactory);
    }

    @Test
    void cancel_stagedFileDeleteFails_isLoggedNotRethrown() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        StorageLocation location = StorageLocation.builder().storageLocationId(UUID.randomUUID()).build();
        stubLockedBatch(batch, List.of(item(batch, 1, "a.pdf", location.getStorageLocationId(), "imports/key-1")));
        when(storageLocationRepository.findByStorageLocationIdAndDeletedFalse(location.getStorageLocationId()))
                .thenReturn(Optional.of(location));
        when(objectStorageServiceFactory.resolve(location)).thenReturn(storage);
        doThrow(new IllegalStateException("storage down")).when(storage).delete(any(), anyString());

        assertThatCode(() -> inTransaction(() -> service(realWorkbook).cancel(batch.getImportBatchId()), () -> { }))
                .doesNotThrowAnyException();

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.cancelled);
        verify(storage).delete(location, "imports/key-1");
    }

    @Test
    void getActive_returnsCallersActiveBatchWithResumeDetails() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        when(importBatchRepository.findFirstByTenantIdAndCreatedByAndStatusInAndDeletedFalseOrderByCreatedAtDesc(
                tenantId, userId, List.of(ImportBatchStatus.uploading, ImportBatchStatus.queued,
                        ImportBatchStatus.running)))
                .thenReturn(Optional.of(batch));
        when(importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batch.getImportBatchId()))
                .thenReturn(List.of(item(batch, 1, "a.pdf", UUID.randomUUID(), "imports/key-1"),
                        item(batch, 2, "b.pdf", null, null)));

        Optional<BulkImportStatusResponse> active = service(realWorkbook).getActive();

        assertThat(active).hasValueSatisfying(response -> {
            assertThat(response.batchId()).isEqualTo(batch.getImportBatchId());
            assertThat(response.documentTypeId()).isEqualTo(documentTypeId);
            assertThat(response.createdAt()).isEqualTo(batch.getCreatedAt());
            assertThat(response.status()).isEqualTo("uploading");
            assertThat(response.filesReceived()).isEqualTo(1);
            assertThat(response.missingFilenames()).containsExactly("b.pdf");
        });
    }

    @Test
    void getActive_noActiveBatch_returnsEmpty() {
        when(importBatchRepository.findFirstByTenantIdAndCreatedByAndStatusInAndDeletedFalseOrderByCreatedAtDesc(
                any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(service(realWorkbook).getActive()).isEmpty();
        verifyNoInteractions(importItemRepository);
    }

    /**
     * Runs {@code action} with transaction synchronization active, runs
     * {@code beforeCommit} while the "transaction" is still open, then
     * fires afterCommit on whatever the action registered.
     */
    private static <T> T inTransaction(Supplier<T> action, Runnable beforeCommit) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            T result = action.get();
            beforeCommit.run();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            return result;
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void stubLockedBatch(ImportBatch batch, List<ImportItem> items) {
        when(importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(batch.getImportBatchId(), tenantId,
                userId)).thenReturn(Optional.of(batch));
        when(importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batch.getImportBatchId()))
                .thenReturn(items);
    }

    private ImportBatch batch(ImportBatchStatus status) {
        return ImportBatch.builder()
                .importBatchId(UUID.randomUUID())
                .tenantId(tenantId)
                .sourceType(ImportBatch.SourceType.bulk_import)
                .documentTypeId(documentTypeId)
                .schemaVersion(3)
                .status(status)
                .totalRecords(2)
                .createdBy(userId)
                .createdAt(OffsetDateTime.now().minusMinutes(5))
                .build();
    }

    private ImportItem item(ImportBatch batch, int rowNumber, String filename, UUID locationId, String objectKey) {
        return ImportItem.builder()
                .importItemId(UUID.randomUUID())
                .tenantId(tenantId)
                .importBatchId(batch.getImportBatchId())
                .rowNumber(rowNumber)
                .filenameReference(filename)
                .stagedObjectKey(objectKey)
                .stagedStorageLocationId(locationId)
                .stagedSizeBytes(objectKey == null ? null : 1024L)
                .status(ImportItem.ImportItemStatus.pending)
                .build();
    }

    private BulkImportServiceImpl service(ImportTemplateWorkbook workbook) {
        return new BulkImportServiceImpl(importBatchRepository, importItemRepository, documentTypeRepository,
                documentTypeFieldService, workbook, new ImportFilenameMatcher(),
                mock(StorageLocationResolver.class), objectStorageServiceFactory, storageLocationRepository,
                mock(OutboxEventPublisher.class), new ImportExportProperties(), new ObjectMapper());
    }

    private DocumentTypeSchemaResponse schema(int version) {
        return new DocumentTypeSchemaResponse(documentTypeId, "INV", "Invoice", version, List.of(
                new SchemaField(UUID.randomUUID(), "reference", "Reference", null, "text", "active",
                        false, false, 10, null, null, List.of(), List.of())));
    }

    private DocumentType option(String typeCode, String typeName, DocumentType.DocumentTypeStatus status) {
        return DocumentType.builder()
                .documentTypeId(UUID.randomUUID())
                .tenantId(tenantId)
                .typeCode(typeCode)
                .typeName(typeName)
                .status(status)
                .build();
    }

    private DocumentType documentType(int currentSchemaVersion, DocumentType.DocumentTypeStatus status) {
        return DocumentType.builder()
                .documentTypeId(documentTypeId)
                .tenantId(tenantId)
                .typeCode("INV")
                .typeName("Invoice")
                .currentSchemaVersion(currentSchemaVersion)
                .status(status)
                .build();
    }
}
