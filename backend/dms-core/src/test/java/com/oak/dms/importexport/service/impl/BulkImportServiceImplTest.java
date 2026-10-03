package com.oak.dms.importexport.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.ApiException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.BulkImportSummary;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.dto.ImportRowProblem;
import com.oak.dms.importexport.dto.ImportTemplateFile;
import com.oak.dms.importexport.dto.ImportTemplatePreview;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.exception.ActiveImportBatchLimitException;
import com.oak.dms.importexport.exception.ImportBatchNotCancellableException;
import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.exception.ImportFailedRowsUnavailableException;
import com.oak.dms.importexport.exception.ImportFileNotInBatchException;
import com.oak.dms.importexport.exception.InactiveDocumentTypeException;
import com.oak.dms.importexport.exception.InvalidImportTemplateException;
import com.oak.dms.importexport.matching.ImportFilenameMatcher;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.importexport.template.ImportTemplateWorkbook;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedRow;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.ParsedTemplate;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.PrefilledRow;
import com.oak.dms.importexport.template.ImportTemplateWorkbook.TemplateContent;
import com.oak.dms.importexport.validation.ImportField;
import com.oak.dms.importexport.validation.ImportRowError;
import com.oak.dms.importexport.validation.ImportRowValidator;
import com.oak.dms.importexport.validation.ImportSchemaFields;
import com.oak.dms.importexport.validation.ImportUsers;
import com.oak.dms.importexport.validation.ImportValueConverter;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.user.UserRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.entity.DocumentType;
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
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
    private final StorageLocationResolver storageLocationResolver = mock(StorageLocationResolver.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final ImportSchemaFields importSchemaFields = mock(ImportSchemaFields.class);
    private final ImportTemplateWorkbook realWorkbook = new ImportTemplateWorkbook();
    private final MetadataValidationService metadataValidationService = mock(MetadataValidationService.class);
    private final UserRepository userRepository = mock(UserRepository.class);

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
    void previewTemplate_reportsProblemRowsWithLabelsInRowOrderAndWritesNothing() {
        stubActiveTypeAndSchema();
        stubReferenceRule();
        byte[] template = filledTemplate(
                new String[]{"a.pdf", "Invoice A", "OK-1"},
                new String[]{"b.pdf", "Invoice B", "BAD"},
                new String[]{null, "No file name", "OK-2"},
                new String[]{"d.pdf", null, null});

        ImportTemplatePreview preview = service(realWorkbook).previewTemplate(template.length,
                new ByteArrayInputStream(template));

        assertThat(preview.documentTypeId()).isEqualTo(documentTypeId);
        assertThat(preview.schemaVersion()).isEqualTo(3);
        assertThat(preview.totalRows()).isEqualTo(4);
        assertThat(preview.validRows()).isEqualTo(2);
        assertThat(preview.invalidRows()).isEqualTo(2);
        assertThat(preview.rows()).extracting(ImportRowFailure::rowNumber).containsExactly(3, 4);
        assertThat(preview.rows().get(0)).isEqualTo(new ImportRowFailure(3, "b.pdf", "Invoice B", List.of(
                new ImportRowProblem("reference", "Reference", "PATTERN", "Reference is not valid", "error"))));
        assertThat(preview.rows().get(1).filename()).isEmpty();
        assertThat(preview.rows().get(1).problems()).extracting(ImportRowProblem::fieldLabel, ImportRowProblem::code)
                .containsExactly(tuple("Filename", "FILENAME_MISSING"));
        // Never the file check - nothing has been uploaded yet.
        assertThat(preview.rows()).flatExtracting(ImportRowFailure::problems).extracting(ImportRowProblem::code)
                .doesNotContain("FILE_MISSING");

        verifyNoInteractions(importBatchRepository, importItemRepository);
    }

    @Test
    void previewTemplate_noProblems_returnsNoRows() {
        stubActiveTypeAndSchema();
        byte[] template = filledTemplate(new String[]{"a.pdf", null, "OK"}, new String[]{"b.pdf", null, null});

        ImportTemplatePreview preview = service(realWorkbook).previewTemplate(template.length,
                new ByteArrayInputStream(template));

        assertThat(preview.validRows()).isEqualTo(2);
        assertThat(preview.invalidRows()).isZero();
        assertThat(preview.rows()).isEmpty();
    }

    @Test
    void previewTemplate_ignoresTheActiveBatchLimit() {
        stubActiveTypeAndSchema();
        when(importBatchRepository.countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(any(), any(), any()))
                .thenReturn(5L);
        byte[] template = filledTemplate(new String[]{"a.pdf", null, null});

        assertThat(service(realWorkbook).previewTemplate(template.length, new ByteArrayInputStream(template))
                .totalRows()).isEqualTo(1);
    }

    @Test
    void previewAndCreate_templateLevelProblems_rejectIdentically() {
        record Case(String name, Runnable stub, byte[] template, long size) {
        }
        byte[] blankV3 = realWorkbook.generate(schema(3), 500);
        byte[] filledV2 = realWorkbook.generate(schema(2), 500);
        List<Case> cases = List.of(
                new Case("too large", () -> { }, new byte[0], 6L * 1024 * 1024),
                new Case("not a workbook", () -> { }, "not xlsx".getBytes(StandardCharsets.US_ASCII), 8),
                new Case("type not in tenant", () -> when(documentTypeRepository
                        .findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                        .thenReturn(Optional.empty()), blankV3, blankV3.length),
                new Case("inactive type", () -> when(documentTypeRepository
                        .findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                        .thenReturn(Optional.of(documentType(3, DocumentType.DocumentTypeStatus.inactive))),
                        blankV3, blankV3.length),
                new Case("outdated", () -> when(documentTypeRepository
                        .findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                        .thenReturn(Optional.of(documentType(3, DocumentType.DocumentTypeStatus.active))),
                        filledV2, filledV2.length),
                new Case("no rows", () -> when(documentTypeRepository
                        .findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                        .thenReturn(Optional.of(documentType(3, DocumentType.DocumentTypeStatus.active))),
                        blankV3, blankV3.length));

        for (Case c : cases) {
            c.stub().run();
            Throwable fromPreview = catchThrowable(() -> service(realWorkbook)
                    .previewTemplate(c.size(), new ByteArrayInputStream(c.template())));
            Throwable fromCreate = catchThrowable(() -> service(realWorkbook)
                    .createBatch(c.size(), new ByteArrayInputStream(c.template())));

            assertThat(fromPreview).as(c.name()).isNotNull();
            assertThat(fromCreate).as(c.name())
                    .isExactlyInstanceOf(fromPreview.getClass())
                    .hasMessage(fromPreview.getMessage())
                    .extracting("code").isEqualTo(((ApiException) fromPreview)
                            .getCode());
        }
        verifyNoInteractions(importItemRepository);
        verify(importBatchRepository, never()).save(any());
    }

    @Test
    void createBatch_invalidRows_savedFailedAndSkippedWithErrorsAndLeftOutOfExpectedFiles() throws Exception {
        stubActiveTypeAndSchema();
        stubReferenceRule();
        stubBatchSave();
        byte[] template = filledTemplate(
                new String[]{"a.pdf", "A", "OK"},
                new String[]{"b.pdf", "B", "BAD"},
                new String[]{"c.pdf", "C", null});

        BulkImportCreatedResponse created = service(realWorkbook).createBatch(template.length,
                new ByteArrayInputStream(template));

        assertThat(created.totalRecords()).isEqualTo(3);
        assertThat(created.skippedRows()).isEqualTo(1);
        assertThat(created.expectedFilenames()).containsExactly("a.pdf", "c.pdf");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImportItem>> saved = ArgumentCaptor.forClass(List.class);
        verify(importItemRepository).saveAll(saved.capture());
        List<ImportItem> items = saved.getValue();
        assertThat(items).extracting(ImportItem::getRowNumber).containsExactly(2, 3, 4);
        assertThat(items).extracting(ImportItem::getImportBatchId).containsOnly(created.batchId());
        assertThat(items).extracting(ImportItem::getStatus).containsExactly(ImportItem.ImportItemStatus.pending,
                ImportItem.ImportItemStatus.failed, ImportItem.ImportItemStatus.pending);
        assertThat(items).extracting(ImportItem::isSkippedAtPreview).containsExactly(false, true, false);
        assertThat(items.get(0).getValidationErrors()).isNull();
        List<ImportRowError> errors = new ObjectMapper().readValue(items.get(1).getValidationErrors(),
                new TypeReference<List<ImportRowError>>() {
                });
        assertThat(errors).containsExactly(
                new ImportRowError("reference", "PATTERN", "Reference is not valid", "error"));
    }

    @Test
    void createBatch_everyRowInvalid_rejectsWithNoValidRowsAndSavesNothing() {
        stubActiveTypeAndSchema();
        stubReferenceRule();
        byte[] template = filledTemplate(new String[]{"a.pdf", null, "BAD"}, new String[]{null, "x", null});

        assertThatThrownBy(() -> service(realWorkbook).createBatch(template.length,
                new ByteArrayInputStream(template)))
                .isInstanceOf(ImportBatchSizeException.class)
                .extracting("code", "status").containsExactly("IMPORT_NO_VALID_ROWS", HttpStatus.BAD_REQUEST);

        verify(importBatchRepository, never()).save(any());
        verify(importItemRepository, never()).saveAll(anyList());
    }

    @Test
    void createBatch_activeBatchLimitStillApplies() {
        stubActiveTypeAndSchema();
        when(importBatchRepository.countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(any(), any(), any()))
                .thenReturn(5L);
        byte[] template = filledTemplate(new String[]{"a.pdf", null, null});

        assertThatThrownBy(() -> service(realWorkbook).createBatch(template.length,
                new ByteArrayInputStream(template)))
                .isInstanceOf(ActiveImportBatchLimitException.class);
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
        assertThat(batch.getCancelReason()).isEqualTo(ImportBatch.CancelReason.user);
        assertThat(batch.getUpdatedBy()).isEqualTo(userId);
        assertThat(staged.getStagedObjectKey()).isNull();
        assertThat(staged.getStagedStorageLocationId()).isNull();
        assertThat(staged.getStagedSizeBytes()).isNull();
        assertThat(response.status()).isEqualTo("cancelled");
        assertThat(response.cancelReason()).isEqualTo("user");
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
        assertThat(batch.getCancelReason()).isEqualTo(ImportBatch.CancelReason.user);
        assertThat(response.status()).isEqualTo("cancelled");
        assertThat(response.cancelReason()).isEqualTo("user");
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

    @Test
    void listBatches_queriesOnlyCallersBatchesInTenant() {
        stubBatchPage(List.of());

        service(realWorkbook).listBatches(PageRequest.of(0, 10));

        verify(importBatchRepository).findAllByTenantIdAndCreatedByAndDeletedFalse(eq(tenantId), eq(userId),
                any(Pageable.class));
        verifyNoMoreInteractions(importBatchRepository);
    }

    @Test
    void listBatches_sortsNewestFirstAndPassesPagingThrough() {
        stubBatchPage(List.of());

        service(realWorkbook).listBatches(PageRequest.of(3, 10, Sort.by("status").ascending()));

        Pageable sent = capturedListPageable();
        assertThat(sent.getPageNumber()).isEqualTo(3);
        assertThat(sent.getPageSize()).isEqualTo(10);
        // The client's sort is replaced, never merged.
        assertThat(sent.getSort()).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("importBatchId"));
    }

    @Test
    void listBatches_requestedSizeOver50_isCappedAt50() {
        stubBatchPage(List.of());
        BulkImportServiceImpl service = service(realWorkbook);

        service.listBatches(PageRequest.of(2, 500));
        Pageable capped = capturedListPageable();
        assertThat(capped.getPageNumber()).isEqualTo(2);
        assertThat(capped.getPageSize()).isEqualTo(50);

        clearInvocations(importBatchRepository);
        service.listBatches(PageRequest.of(0, 50));
        assertThat(capturedListPageable().getPageSize()).isEqualTo(50);
    }

    @Test
    void listBatches_includesEveryStatusInRepositoryOrder() {
        List<ImportBatch> batches = Arrays.stream(ImportBatchStatus.values()).map(this::batch).toList();
        stubBatchPage(batches);
        when(documentTypeRepository.findAllById(any())).thenReturn(List.of(documentType(1,
                DocumentType.DocumentTypeStatus.active)));

        Page<BulkImportSummary> page = service(realWorkbook).listBatches(PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(BulkImportSummary::status)
                .containsExactly(Arrays.stream(ImportBatchStatus.values()).map(Enum::name).toArray(String[]::new));
        assertThat(page.getContent()).extracting(BulkImportSummary::batchId)
                .containsExactlyElementsOf(batches.stream().map(ImportBatch::getImportBatchId).toList());
    }

    @Test
    void listBatches_mapsTypeNamesWithOneLookupForThePage() {
        DocumentType invoice = documentType(1, DocumentType.DocumentTypeStatus.active);
        DocumentType contract = option("CON", "Contract", DocumentType.DocumentTypeStatus.inactive);
        DocumentType foreign = option("FOR", "Other tenant's type", DocumentType.DocumentTypeStatus.active);
        foreign.setTenantId(UUID.randomUUID());

        ImportBatch first = batch(ImportBatchStatus.completed);
        ImportBatch second = batch(ImportBatchStatus.failed);
        ImportBatch third = batch(ImportBatchStatus.cancelled);
        third.setDocumentTypeId(contract.getDocumentTypeId());
        ImportBatch fourth = batch(ImportBatchStatus.completed_with_errors);
        fourth.setDocumentTypeId(foreign.getDocumentTypeId());
        first.setSuccessCount(2);
        first.setFailureCount(6);
        first.setTotalRecords(8);
        first.setCompletedAt(OffsetDateTime.now());
        stubBatchPage(List.of(first, second, third, fourth));
        when(documentTypeRepository.findAllById(any())).thenReturn(List.of(invoice, contract, foreign));

        List<BulkImportSummary> rows = service(realWorkbook).listBatches(PageRequest.of(0, 10)).getContent();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<UUID>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(documentTypeRepository).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(documentTypeId, contract.getDocumentTypeId(),
                foreign.getDocumentTypeId());
        assertThat(rows).extracting(BulkImportSummary::documentTypeName)
                .containsExactly("Invoice", "Invoice", "Contract", null);
        BulkImportSummary summary = rows.get(0);
        assertThat(summary.documentTypeId()).isEqualTo(documentTypeId);
        assertThat(summary.totalRecords()).isEqualTo(8);
        assertThat(summary.successCount()).isEqualTo(2);
        assertThat(summary.failureCount()).isEqualTo(6);
        assertThat(summary.createdAt()).isEqualTo(first.getCreatedAt());
        assertThat(summary.completedAt()).isEqualTo(first.getCompletedAt());
    }

    @Test
    void listBatches_keepsPageMetadata() {
        ImportBatch batch = batch(ImportBatchStatus.completed);
        when(importBatchRepository.findAllByTenantIdAndCreatedByAndDeletedFalse(any(), any(), any()))
                .thenAnswer(call -> new PageImpl<>(List.of(batch), call.getArgument(2), 21));
        when(documentTypeRepository.findAllById(any())).thenReturn(List.of());

        Page<BulkImportSummary> page = service(realWorkbook).listBatches(PageRequest.of(2, 10));

        assertThat(page.getNumber()).isEqualTo(2);
        assertThat(page.getSize()).isEqualTo(10);
        assertThat(page.getTotalElements()).isEqualTo(21);
        assertThat(page.isLast()).isTrue();
    }

    @Test
    void listBatches_emptyPage_skipsTypeLookup() {
        stubBatchPage(List.of());

        assertThat(service(realWorkbook).listBatches(PageRequest.of(0, 10)).getContent()).isEmpty();
        verifyNoInteractions(documentTypeRepository);
    }

    @Test
    void receiveFile_stagesInTenantStagingLocation() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem item = item(batch, 2, "a.pdf", null, null);
        stubLockedBatch(batch, List.of(item));
        StorageLocation staging = StorageLocation.builder()
                .storageLocationId(UUID.randomUUID())
                .purpose(StoragePurpose.staging)
                .build();
        when(storageLocationResolver.resolveForTenant(tenantId, StoragePurpose.staging)).thenReturn(staging);
        when(objectStorageServiceFactory.resolve(staging)).thenReturn(storage);
        byte[] content = "%PDF-1.7\n%test".getBytes(StandardCharsets.US_ASCII);
        String expectedKey = "imports/" + tenantId + "/" + batch.getImportBatchId() + "/" + item.getImportItemId();
        when(storage.upload(eq(staging), eq(expectedKey), any(), anyLong(), eq("application/pdf")))
                .thenReturn(new StoredObjectMetadata(expectedKey, content.length, "application/pdf", null, null));

        service(realWorkbook).receiveFile(batch.getImportBatchId(), "a.pdf", "application/octet-stream",
                content.length, new ByteArrayInputStream(content));

        verify(storage).upload(eq(staging), eq(expectedKey), any(), eq((long) content.length), eq("application/pdf"));
        verify(storageLocationResolver, never()).resolveForTenant(any());
        assertThat(item.getStagedStorageLocationId()).isEqualTo(staging.getStorageLocationId());
        assertThat(item.getStagedObjectKey()).isEqualTo(expectedKey);
    }

    @Test
    void receiveFile_fileForRowSkippedAtPreview_rejectsAndUploadsNothing() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem skipped = skippedItem(batch, 5, "bad.pdf");
        stubLockedBatch(batch, List.of(item(batch, 2, "a.pdf", null, null), skipped));
        byte[] content = "%PDF-1.7\n%test".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> service(realWorkbook).receiveFile(batch.getImportBatchId(), "bad.pdf",
                "application/pdf", content.length, new ByteArrayInputStream(content)))
                .isInstanceOf(ImportFileNotInBatchException.class)
                .hasMessage("Row 5 was skipped because of template errors, so it doesn't need a file.")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(storageLocationResolver, objectStorageServiceFactory, storage);
        verify(importItemRepository, never()).save(any());
        assertThat(skipped.getStagedObjectKey()).isNull();
    }

    @Test
    void getStatus_rowsSkippedAtPreviewNeitherMissingNorReceived_workerFailedRowStillMissing() {
        ImportBatch batch = batch(ImportBatchStatus.running);
        ImportItem received = item(batch, 2, "a.pdf", UUID.randomUUID(), "imports/key-1");
        ImportItem waiting = item(batch, 3, "b.pdf", null, null);
        ImportItem skipped = skippedItem(batch, 4, "c.pdf");
        // Failed by the worker (no file arrived) - not skipped, so still missing.
        ImportItem workerFailed = failedItem(batch, 5, "d.pdf", "{\"title\":null,\"fields\":{}}",
                "[{\"field\":\"file\",\"code\":\"FILE_MISSING\",\"message\":\"No file\",\"severity\":\"error\"}]");
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batch.getImportBatchId()))
                .thenReturn(List.of(received, waiting, skipped, workerFailed));

        BulkImportStatusResponse status = service(realWorkbook).getStatus(batch.getImportBatchId());

        assertThat(workerFailed.isSkippedAtPreview()).isFalse();
        assertThat(status.skippedRows()).isEqualTo(1);
        assertThat(status.filesReceived()).isEqualTo(1);
        assertThat(status.missingFilenames()).containsExactly("b.pdf", "d.pdf");
    }

    @Test
    void receiveFile_contentNotMatchingExtension_rejects415AndUploadsNothing() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem item = item(batch, 2, "a.pdf", null, null);
        stubLockedBatch(batch, List.of(item));
        byte[] zip = {'P', 'K', 3, 4, 0, 0, 0, 0};

        assertThatThrownBy(() -> service(realWorkbook).receiveFile(batch.getImportBatchId(), "a.pdf",
                "application/pdf", zip.length, new ByteArrayInputStream(zip)))
                .isInstanceOf(FileTypeNotAllowedException.class)
                .extracting("status").isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        verifyNoInteractions(storageLocationResolver, objectStorageServiceFactory, storage);
        verify(importItemRepository).save(item);
        assertThat(item.getStagedObjectKey()).isNull();
        assertThat(item.getUploadRejectionCode()).isEqualTo("FILE_TYPE_NOT_ALLOWED");
        assertThat(item.getUploadRejectionMessage()).isEqualTo("The file you uploaded ('a.pdf') was rejected: "
                + "The file's content does not match its .pdf extension");
    }

    @Test
    void receiveFile_emptyFile_rejects415AndSavesRejectionOnRow() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem item = item(batch, 2, "contract.pdf", null, null);
        stubLockedBatch(batch, List.of(item));

        assertThatThrownBy(() -> service(realWorkbook).receiveFile(batch.getImportBatchId(), "Contract.pdf",
                "application/pdf", 0, new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(FileTypeNotAllowedException.class)
                .hasMessage("The uploaded file is empty")
                .extracting("status").isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        verifyNoInteractions(storageLocationResolver, objectStorageServiceFactory, storage);
        verify(importItemRepository).save(item);
        assertThat(item.getStagedObjectKey()).isNull();
        assertThat(item.getUploadRejectionCode()).isEqualTo("FILE_TYPE_NOT_ALLOWED");
        // Names the file as uploaded, not the row's filename.
        assertThat(item.getUploadRejectionMessage())
                .isEqualTo("The file you uploaded ('Contract.pdf') was rejected: The uploaded file is empty");
    }

    @Test
    void receiveFile_overBatchSizeLimit_rejects400AndSavesRejectionOnRow() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem staged = item(batch, 2, "a.pdf", UUID.randomUUID(), "imports/key-1");
        ImportItem item = item(batch, 3, "b.pdf", null, null);
        stubLockedBatch(batch, List.of(staged, item));
        ImportExportProperties properties = new ImportExportProperties();
        properties.setMaxBatchSize(DataSize.ofBytes(1024 + 10));
        byte[] content = "%PDF-1.7\n%test".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> service(realWorkbook, properties).receiveFile(batch.getImportBatchId(), "b.pdf",
                "application/pdf", content.length, new ByteArrayInputStream(content)))
                .isInstanceOf(ImportBatchSizeException.class)
                .hasFieldOrPropertyWithValue("code", "IMPORT_BATCH_SIZE_EXCEEDED")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(storageLocationResolver, objectStorageServiceFactory, storage);
        verify(importItemRepository).save(item);
        assertThat(item.getStagedObjectKey()).isNull();
        assertThat(item.getUploadRejectionCode()).isEqualTo("IMPORT_BATCH_SIZE_EXCEEDED");
        assertThat(item.getUploadRejectionMessage()).startsWith("The file you uploaded ('b.pdf') was rejected: "
                + "Adding this file would take the import over its");
        assertThat(staged.getUploadRejectionCode()).isNull();
    }

    @Test
    void receiveFile_rejectionsCommitButEveryOtherFailureRollsBack() throws NoSuchMethodException {
        // The rejection is saved in the request's own transaction, which is
        // committed rather than rolled back for these two exceptions only.
        TransactionAttribute receive = new AnnotationTransactionAttributeSource().getTransactionAttribute(
                BulkImportServiceImpl.class.getMethod("receiveFile", UUID.class, String.class, String.class,
                        long.class, InputStream.class), BulkImportServiceImpl.class);
        assertThat(receive).isNotNull();
        assertThat(receive.rollbackOn(new FileTypeNotAllowedException("x"))).isFalse();
        assertThat(receive.rollbackOn(new ImportBatchSizeException("IMPORT_BATCH_SIZE_EXCEEDED", "x"))).isFalse();
        assertThat(receive.rollbackOn(new ImportFileNotInBatchException("x"))).isTrue();
        assertThat(receive.rollbackOn(new UncheckedIOException(new IOException("x")))).isTrue();

        // Only on receiveFile: createBatch still rolls back on the same size exception.
        TransactionAttribute create = new AnnotationTransactionAttributeSource().getTransactionAttribute(
                BulkImportServiceImpl.class.getMethod("createBatch", long.class, InputStream.class),
                BulkImportServiceImpl.class);
        assertThat(create.rollbackOn(new ImportBatchSizeException("IMPORT_ROW_COUNT_INVALID", "x"))).isTrue();
    }

    @Test
    void receiveFile_acceptedAfterRejection_clearsTheRejection() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem item = item(batch, 2, "a.pdf", null, null);
        stubLockedBatch(batch, List.of(item));
        BulkImportServiceImpl service = service(realWorkbook);
        byte[] zip = {'P', 'K', 3, 4, 0, 0, 0, 0};
        assertThatThrownBy(() -> service.receiveFile(batch.getImportBatchId(), "a.pdf", "application/pdf",
                zip.length, new ByteArrayInputStream(zip))).isInstanceOf(FileTypeNotAllowedException.class);
        assertThat(item.getUploadRejectionCode()).isNotNull();

        StorageLocation staging = StorageLocation.builder()
                .storageLocationId(UUID.randomUUID())
                .purpose(StoragePurpose.staging)
                .build();
        when(storageLocationResolver.resolveForTenant(tenantId, StoragePurpose.staging)).thenReturn(staging);
        when(objectStorageServiceFactory.resolve(staging)).thenReturn(storage);
        byte[] content = "%PDF-1.7\n%test".getBytes(StandardCharsets.US_ASCII);
        when(storage.upload(eq(staging), anyString(), any(), anyLong(), eq("application/pdf")))
                .thenReturn(new StoredObjectMetadata("key", content.length, "application/pdf", null, null));

        service.receiveFile(batch.getImportBatchId(), "a.pdf", "application/pdf", content.length,
                new ByteArrayInputStream(content));

        verify(importItemRepository, times(2)).save(item);
        assertThat(item.getStagedObjectKey()).isNotNull();
        assertThat(item.getUploadRejectionCode()).isNull();
        assertThat(item.getUploadRejectionMessage()).isNull();
    }

    @Test
    void receiveFile_passwordProtectedDocx_rejects415AndUploadsNothing() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem item = item(batch, 2, "a.docx", null, null);
        stubLockedBatch(batch, List.of(item));
        // Office stores a password-protected .docx as an OLE2 compound file.
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};

        assertThatThrownBy(() -> service(realWorkbook).receiveFile(batch.getImportBatchId(), "a.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ole2.length,
                new ByteArrayInputStream(ole2)))
                .isInstanceOf(FileTypeNotAllowedException.class)
                .hasMessageContaining("Password-protected")
                .extracting("status").isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        verifyNoInteractions(storageLocationResolver, objectStorageServiceFactory, storage);
        verify(importItemRepository).save(item);
        assertThat(item.getStagedObjectKey()).isNull();
        assertThat(item.getUploadRejectionCode()).isEqualTo("FILE_TYPE_NOT_ALLOWED");
        assertThat(item.getUploadRejectionMessage())
                .startsWith("The file you uploaded ('a.docx') was rejected: Password-protected files");
    }

    @Test
    void getFailures_returnsOnlyFailedRowsInRowOrder() {
        ImportBatch batch = batch(ImportBatchStatus.completed_with_errors);
        ImportItem row2 = failedItem(batch, 2, "a.pdf", "{\"title\":\"Invoice A\",\"fields\":{}}",
                "[{\"field\":\"file\",\"code\":\"FILE_MISSING\",\"message\":\"No file was uploaded for this row\","
                        + "\"severity\":\"error\"}]");
        ImportItem row5 = failedItem(batch, 5, "b.pdf", "{\"title\":null,\"fields\":{}}",
                "[{\"field\":\"filename\",\"code\":\"DUPLICATE_FILENAME\",\"message\":\"Duplicate\","
                        + "\"severity\":\"error\"}]");
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItem.ImportItemStatus.failed)).thenReturn(List.of(row2, row5));
        when(importSchemaFields.load(tenantId, documentTypeId, 3)).thenReturn(Map.of());

        List<ImportRowFailure> failures = service(realWorkbook).getFailures(batch.getImportBatchId());

        assertThat(failures).extracting(ImportRowFailure::rowNumber).containsExactly(2, 5);
        assertThat(failures).extracting(ImportRowFailure::filename).containsExactly("a.pdf", "b.pdf");
        assertThat(failures).extracting(ImportRowFailure::title).containsExactly("Invoice A", null);
        assertThat(failures.get(0).problems()).containsExactly(new ImportRowProblem("file", "File", "FILE_MISSING",
                "No file was uploaded for this row", "error"));
        verify(importItemRepository, never()).findAllByImportBatchIdOrderByRowNumberAsc(any());
    }

    @Test
    void getFailures_mapsFieldLabelsFromBatchSchemaVersionOwnFieldsAndUnknownKeys() {
        ImportBatch batch = batch(ImportBatchStatus.completed_with_errors);
        ImportItem row = failedItem(batch, 3, "c.pdf", "{\"title\":\"C\",\"fields\":{}}", """
                [{"field":"reference","code":"REQUIRED","message":"Reference is required","severity":"error"},
                 {"field":"file","code":"FILE_INFECTED","message":"Malware found","severity":"error"},
                 {"field":"filename","code":"FILENAME_MISSING","message":"Enter the file name","severity":"error"},
                 {"field":"title","code":"TITLE_TOO_LONG","message":"Title too long","severity":"error"},
                 {"field":"gone_field","code":"UNKNOWN","message":"Not in schema","severity":"warning"}]
                """);
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItem.ImportItemStatus.failed)).thenReturn(List.of(row));
        when(importSchemaFields.load(tenantId, documentTypeId, 3)).thenReturn(Map.of("reference",
                new ImportField(UUID.randomUUID(), "reference", "Reference", "text", true, false)));

        List<ImportRowProblem> problems = service(realWorkbook).getFailures(batch.getImportBatchId()).get(0)
                .problems();

        assertThat(problems).extracting(ImportRowProblem::fieldLabel)
                .containsExactly("Reference", "File", "Filename", "Title", "gone_field");
        assertThat(problems.get(4)).isEqualTo(new ImportRowProblem("gone_field", "gone_field", "UNKNOWN",
                "Not in schema", "warning"));
    }

    @Test
    void getFailures_batchOfAnotherUserOrTenant_notFoundAndReadsNothingElse() {
        UUID foreignBatchId = UUID.randomUUID();
        when(importBatchRepository.findByImportBatchIdAndTenantIdAndCreatedByAndDeletedFalse(foreignBatchId,
                tenantId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(realWorkbook).getFailures(foreignBatchId))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(importItemRepository, importSchemaFields);
    }

    @Test
    void getFailures_noFailedRows_returnsEmptyWithoutLoadingSchema() {
        ImportBatch batch = batch(ImportBatchStatus.completed);
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItem.ImportItemStatus.failed)).thenReturn(List.of());

        assertThat(service(realWorkbook).getFailures(batch.getImportBatchId())).isEmpty();
        verifyNoInteractions(importSchemaFields);
    }

    @Test
    void generateFailedRowsTemplate_currentSchema_carriesValuesByKeyDropsRemovedFieldsAndListsErrors()
            throws IOException {
        ImportBatch batch = batch(ImportBatchStatus.completed_with_errors);
        ImportItem row2 = failedItem(batch, 2, "a.pdf",
                "{\"title\":\"Invoice A\",\"fields\":{\"reference\":\"R-1\",\"gone_field\":\"x\"}}", """
                        [{"field":"reference","code":"REQUIRED","message":"This field is required","severity":"error"},
                         {"field":"file","code":"FILE_MISSING","message":"No file was uploaded","severity":"error"}]
                        """);
        ImportItem row5 = failedItem(batch, 5, "b.pdf", "{\"title\":null,\"fields\":{}}",
                "[{\"field\":null,\"code\":\"X\",\"message\":\"Something else\",\"severity\":\"error\"}]");
        stubOwnBatch(batch);
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.of(documentType(4, DocumentType.DocumentTypeStatus.active)));
        // v4: "reference" kept, "gone_field" removed, "notes" added.
        when(documentTypeFieldService.getSchema(documentTypeId)).thenReturn(new DocumentTypeSchemaResponse(
                documentTypeId, "INV", "Invoice", 4, List.of(
                new SchemaField(UUID.randomUUID(), "reference", "Reference v4", null, "text", "active",
                        false, false, 10, null, null, List.of(), List.of()),
                new SchemaField(UUID.randomUUID(), "notes", "Notes", null, "text", "active",
                        false, false, 20, null, null, List.of(), List.of()))));
        when(importSchemaFields.load(tenantId, documentTypeId, 3)).thenReturn(Map.of("reference",
                new ImportField(UUID.randomUUID(), "reference", "Reference", "text", true, false)));
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItem.ImportItemStatus.failed)).thenReturn(List.of(row2, row5));

        ImportTemplateFile file = service(realWorkbook).generateFailedRowsTemplate(batch.getImportBatchId());

        assertThat(file.filename()).matches("INV-failed-rows-\\d{8}-\\d{4}\\.xlsx");
        ParsedTemplate parsed = realWorkbook.parse(new ByteArrayInputStream(file.content()), 500);
        assertThat(parsed.schemaVersion()).isEqualTo(4);
        assertThat(parsed.resubmitOf()).isEqualTo(batch.getImportBatchId());
        assertThat(parsed.columnKeys()).containsExactly("filename", "title", "reference", "notes");
        assertThat(parsed.rows()).extracting(ParsedRow::filename, ParsedRow::title)
                .containsExactly(tuple("a.pdf", "Invoice A"), tuple("b.pdf", null));
        assertThat(parsed.rows().get(0).fields()).containsExactly(Map.entry("reference", "R-1"));
        assertThat(parsed.rows().get(1).fields()).isEmpty();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.content()))) {
            Sheet sheet = workbook.getSheet(ImportTemplateWorkbook.IMPORT_SHEET);
            assertThat(sheet.getRow(0).getCell(4).getStringCellValue()).isEqualTo("Import errors");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue())
                    // The file message already names its column, so it isn't repeated.
                    .isEqualTo("Reference: This field is required\nNo file was uploaded");
            assertThat(sheet.getRow(2).getCell(4).getStringCellValue()).isEqualTo("Something else");
        }
    }

    @Test
    void errorLines_dropsTheLabelOnlyWhenTheMessageAlreadyContainsIt() {
        assertThat(BulkImportServiceImpl.errorLines(List.of(
                // Label in the message, any case - not repeated.
                new ImportRowProblem("file", "File", "FILE_REJECTED",
                        "The file you uploaded ('contract-fake.pdf') was rejected: The uploaded file is empty",
                        "error"),
                new ImportRowProblem("reference", "Reference", "REQUIRED", "REFERENCE is required", "error"),
                // Label not in the message - prefixed.
                new ImportRowProblem("amount", "Amount", "INVALID_NUMBER", "Must be a number", "error"),
                // No column - message alone.
                new ImportRowProblem(null, null, "X", "Something else", "error"))))
                .isEqualTo("""
                        The file you uploaded ('contract-fake.pdf') was rejected: The uploaded file is empty
                        REFERENCE is required
                        Amount: Must be a number
                        Something else""");
    }

    @Test
    void generateFailedRowsTemplate_inactiveDocumentType_stillDownloads() {
        ImportBatch batch = batch(ImportBatchStatus.failed);
        stubOwnBatch(batch);
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.of(documentType(3, DocumentType.DocumentTypeStatus.inactive)));
        when(documentTypeFieldService.getSchema(documentTypeId)).thenReturn(schema(3));
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItem.ImportItemStatus.failed)).thenReturn(List.of(failedItem(batch, 2, "a.pdf",
                "{\"title\":null,\"fields\":{}}", null)));

        ImportTemplateFile file = service(realWorkbook).generateFailedRowsTemplate(batch.getImportBatchId());

        assertThat(realWorkbook.parse(new ByteArrayInputStream(file.content()), 500).rows())
                .extracting(ParsedRow::filename).containsExactly("a.pdf");
    }

    @Test
    void generateFailedRowsTemplate_batchWithoutFailedRowsStatus_rejectsWith409() {
        for (ImportBatchStatus status : List.of(ImportBatchStatus.uploading, ImportBatchStatus.queued,
                ImportBatchStatus.running, ImportBatchStatus.completed, ImportBatchStatus.cancelled)) {
            ImportBatch batch = batch(status);
            stubOwnBatch(batch);

            assertThatThrownBy(() -> service(realWorkbook).generateFailedRowsTemplate(batch.getImportBatchId()))
                    .as(status.name())
                    .isInstanceOf(ImportFailedRowsUnavailableException.class)
                    .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        }
        verifyNoInteractions(importItemRepository, documentTypeFieldService);
    }

    @Test
    void generateFailedRowsTemplate_batchOfAnotherUser_notFound() {
        UUID foreignBatchId = UUID.randomUUID();
        when(importBatchRepository.findByImportBatchIdAndTenantIdAndCreatedByAndDeletedFalse(foreignBatchId,
                tenantId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(realWorkbook).generateFailedRowsTemplate(foreignBatchId))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(importItemRepository, documentTypeFieldService);
    }

    @Test
    void createBatch_resubmit_movesKeptFilesToMatchingRowsAndClearsThemOnOriginal() {
        stubActiveTypeAndSchema();
        stubReferenceRule();
        stubBatchSave();
        ImportBatch original = finishedOriginal(1);
        UUID locationId = UUID.randomUUID();
        ImportItem keptA = keptItem(original, 2, " A.PDF ", locationId, "imports/k-a");
        ImportItem keptB1 = keptItem(original, 3, "b.pdf", locationId, "imports/k-b1");
        ImportItem keptB2 = keptItem(original, 4, "b.pdf", locationId, "imports/k-b2");
        ImportItem keptD = keptItem(original, 5, "d.pdf", locationId, "imports/k-d");
        stubLockedOriginal(original, List.of(keptA, keptB1, keptB2, keptD));
        keptA.setUploadRejectionCode("FILE_TYPE_NOT_ALLOWED");
        keptA.setUploadRejectionMessage("The file you uploaded ('a.pdf') was rejected: The uploaded file is empty");
        byte[] template = resubmitTemplate(original.getImportBatchId(),
                new String[]{"a.pdf", null, "OK"},
                new String[]{"b.pdf", null, "OK"},
                new String[]{"c.pdf", null, "OK"},
                new String[]{"d.pdf", null, "BAD"});

        BulkImportCreatedResponse created = service(realWorkbook).createBatch(template.length,
                new ByteArrayInputStream(template));

        assertThat(created.reusedFiles()).isEqualTo(2);
        assertThat(created.skippedRows()).isEqualTo(1);
        assertThat(created.expectedFilenames()).containsExactly("c.pdf");

        ArgumentCaptor<ImportBatch> savedBatch = ArgumentCaptor.forClass(ImportBatch.class);
        verify(importBatchRepository).save(savedBatch.capture());
        assertThat(savedBatch.getValue().getResubmitOfBatchId()).isEqualTo(original.getImportBatchId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ImportItem>> saved = ArgumentCaptor.forClass(List.class);
        verify(importItemRepository, times(2)).saveAll(saved.capture());
        List<ImportItem> items = saved.getAllValues().get(0);
        assertThat(items).extracting(ImportItem::getStagedObjectKey)
                .containsExactly("imports/k-a", "imports/k-b1", null, null);
        assertThat(items).extracting(ImportItem::getStagedStorageLocationId)
                .containsExactly(locationId, locationId, null, null);
        assertThat(items).extracting(ImportItem::getStagedSizeBytes).containsExactly(1024L, 1024L, null, null);
        // Only the staged file moves - an intake rejection stays with the original row.
        assertThat(items).extracting(ImportItem::getUploadRejectionCode, ImportItem::getUploadRejectionMessage)
                .containsOnly(tuple(null, null));
        assertThat(saved.getAllValues().get(1)).containsExactly(keptA, keptB1);

        assertThat(keptA.getStagedObjectKey()).isNull();
        assertThat(keptA.getStagedStorageLocationId()).isNull();
        assertThat(keptA.getStagedSizeBytes()).isNull();
        assertThat(keptB1.getStagedObjectKey()).isNull();
        // One file per row (the lowest-numbered one), and a row skipped for
        // template errors claims nothing.
        assertThat(keptB2.getStagedObjectKey()).isEqualTo("imports/k-b2");
        assertThat(keptD.getStagedObjectKey()).isEqualTo("imports/k-d");
    }

    @Test
    void createBatch_secondResubmitOfSameBatch_findsFilesAlreadyClaimed() {
        stubActiveTypeAndSchema();
        stubBatchSave();
        ImportBatch original = finishedOriginal(1);
        ImportItem kept = keptItem(original, 2, "a.pdf", UUID.randomUUID(), "imports/k-a");
        stubLockedOriginal(original, List.of(kept));
        byte[] template = resubmitTemplate(original.getImportBatchId(), new String[]{"a.pdf", null, null});

        BulkImportCreatedResponse first = service(realWorkbook).createBatch(template.length,
                new ByteArrayInputStream(template));
        BulkImportCreatedResponse second = service(realWorkbook).createBatch(template.length,
                new ByteArrayInputStream(template));

        assertThat(first.reusedFiles()).isEqualTo(1);
        assertThat(first.expectedFilenames()).isEmpty();
        assertThat(second.reusedFiles()).isZero();
        assertThat(second.expectedFilenames()).containsExactly("a.pdf");
    }

    @Test
    void createBatch_resubmitAfterRetentionWindow_reusesNothing() {
        stubActiveTypeAndSchema();
        stubBatchSave();
        ImportBatch original = finishedOriginal(25);
        ImportItem kept = keptItem(original, 2, "a.pdf", UUID.randomUUID(), "imports/k-a");
        stubLockedOriginal(original, List.of(kept));
        byte[] template = resubmitTemplate(original.getImportBatchId(), new String[]{"a.pdf", null, null});

        BulkImportCreatedResponse created = service(realWorkbook).createBatch(template.length,
                new ByteArrayInputStream(template));

        assertThat(created.reusedFiles()).isZero();
        assertThat(created.expectedFilenames()).containsExactly("a.pdf");
        assertThat(kept.getStagedObjectKey()).isEqualTo("imports/k-a");
        verify(importItemRepository).saveAll(anyList());
    }

    @Test
    void createBatch_resubmitOfBatchThatCantBeResubmitted_rejects400AndSavesNothing() {
        stubActiveTypeAndSchema();
        record Case(String name, ImportBatch original, boolean visible) {
        }
        ImportBatch otherType = finishedOriginal(1);
        otherType.setDocumentTypeId(UUID.randomUUID());
        ImportBatch completed = finishedOriginal(1);
        completed.setStatus(ImportBatchStatus.completed);
        ImportBatch running = finishedOriginal(1);
        running.setStatus(ImportBatchStatus.running);
        List<Case> cases = List.of(
                new Case("another user's batch", finishedOriginal(1), false),
                new Case("another document type", otherType, true),
                new Case("completed without errors", completed, true),
                new Case("still running", running, true));

        for (Case c : cases) {
            UUID originalId = c.original().getImportBatchId();
            Optional<ImportBatch> found = c.visible() ? Optional.of(c.original()) : Optional.empty();
            when(importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(originalId, tenantId, userId))
                    .thenReturn(found);
            when(importBatchRepository.findByImportBatchIdAndTenantIdAndCreatedByAndDeletedFalse(originalId,
                    tenantId, userId)).thenReturn(found);
            byte[] template = resubmitTemplate(originalId, new String[]{"a.pdf", null, null});

            for (Runnable call : List.<Runnable>of(
                    () -> service(realWorkbook).createBatch(template.length, new ByteArrayInputStream(template)),
                    () -> service(realWorkbook).previewTemplate(template.length,
                            new ByteArrayInputStream(template)))) {
                Throwable thrown = catchThrowable(call::run);
                assertThat(thrown).as(c.name()).isInstanceOf(InvalidImportTemplateException.class);
                assertThat(((ApiException) thrown).getCode()).as(c.name()).isEqualTo("IMPORT_RESUBMIT_INVALID");
                assertThat(((ApiException) thrown).getStatus()).as(c.name()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(thrown.getMessage()).as(c.name())
                        .startsWith("This file came from an import that can't be resubmitted");
            }
        }
        verify(importBatchRepository, never()).save(any());
        verify(importItemRepository, never()).saveAll(anyList());
    }

    @Test
    void previewTemplate_resubmit_reportsReusableFilesWithoutMovingAnything() {
        stubActiveTypeAndSchema();
        stubReferenceRule();
        ImportBatch original = finishedOriginal(1);
        ImportItem keptA = keptItem(original, 2, "a.pdf", UUID.randomUUID(), "imports/k-a");
        ImportItem keptB = keptItem(original, 3, "B.pdf", UUID.randomUUID(), "imports/k-b");
        stubOwnBatch(original);
        when(importItemRepository.findAllByImportBatchIdAndStatusAndStagedObjectKeyIsNotNullOrderByRowNumberAsc(
                original.getImportBatchId(), ImportItem.ImportItemStatus.failed)).thenReturn(List.of(keptA, keptB));
        byte[] template = resubmitTemplate(original.getImportBatchId(),
                new String[]{"a.pdf", null, null},
                new String[]{"b.pdf", null, "BAD"},
                new String[]{"c.pdf", null, null});

        ImportTemplatePreview preview = service(realWorkbook).previewTemplate(template.length,
                new ByteArrayInputStream(template));

        assertThat(preview.resubmitOf()).isEqualTo(original.getImportBatchId());
        assertThat(preview.reusableFiles()).isEqualTo(1);
        assertThat(keptA.getStagedObjectKey()).isEqualTo("imports/k-a");
        assertThat(keptB.getStagedObjectKey()).isEqualTo("imports/k-b");
        verify(importBatchRepository, never()).lockByImportBatchIdAndTenantIdAndCreatedBy(any(), any(), any());
        verify(importBatchRepository, never()).save(any());
        verify(importItemRepository, never()).saveAll(anyList());
    }

    @Test
    void previewTemplate_normalTemplate_hasNoResubmit() {
        stubActiveTypeAndSchema();
        byte[] template = filledTemplate(new String[]{"a.pdf", null, null});

        ImportTemplatePreview preview = service(realWorkbook).previewTemplate(template.length,
                new ByteArrayInputStream(template));

        assertThat(preview.resubmitOf()).isNull();
        assertThat(preview.reusableFiles()).isZero();
    }

    /** A finished-with-errors batch of the caller's, for the same type, completed {@code hoursAgo} ago. */
    private ImportBatch finishedOriginal(int hoursAgo) {
        ImportBatch original = batch(ImportBatchStatus.completed_with_errors);
        original.setCompletedAt(OffsetDateTime.now().minusHours(hoursAgo));
        return original;
    }

    private ImportItem keptItem(ImportBatch original, int rowNumber, String filename, UUID locationId,
                                String objectKey) {
        ImportItem item = item(original, rowNumber, filename, locationId, objectKey);
        item.setStatus(ImportItem.ImportItemStatus.failed);
        return item;
    }

    /** The original batch under its lock; its kept files are read live, so a claimed one is gone next time. */
    private void stubLockedOriginal(ImportBatch original, List<ImportItem> kept) {
        when(importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(original.getImportBatchId(), tenantId,
                userId)).thenReturn(Optional.of(original));
        when(importItemRepository.findAllByImportBatchIdAndStatusAndStagedObjectKeyIsNotNullOrderByRowNumberAsc(
                original.getImportBatchId(), ImportItem.ImportItemStatus.failed))
                .thenAnswer(call -> kept.stream().filter(item -> item.getStagedObjectKey() != null).toList());
    }

    /** A v3 failed-rows template resubmitting {@code originalId}, each row {filename, title, reference}. */
    private byte[] resubmitTemplate(UUID originalId, String[]... rows) {
        List<PrefilledRow> prefilled = Arrays.stream(rows)
                .map(row -> new PrefilledRow(row[0], row[1],
                        row[2] != null ? Map.of("reference", row[2]) : Map.of(), "Reference: was wrong"))
                .toList();
        return realWorkbook.generate(schema(3), 500, new TemplateContent(originalId, prefilled, true));
    }

    private void stubBatchPage(List<ImportBatch> batches) {
        when(importBatchRepository.findAllByTenantIdAndCreatedByAndDeletedFalse(any(), any(), any()))
                .thenAnswer(call -> new PageImpl<>(batches, call.getArgument(2), batches.size()));
    }

    private Pageable capturedListPageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(importBatchRepository).findAllByTenantIdAndCreatedByAndDeletedFalse(eq(tenantId), eq(userId),
                pageable.capture());
        return pageable.getValue();
    }

    private void stubOwnBatch(ImportBatch batch) {
        when(importBatchRepository.findByImportBatchIdAndTenantIdAndCreatedByAndDeletedFalse(batch.getImportBatchId(),
                tenantId, userId)).thenReturn(Optional.of(batch));
    }

    private ImportItem failedItem(ImportBatch batch, int rowNumber, String filename, String rowValues,
                                  String validationErrors) {
        ImportItem item = item(batch, rowNumber, filename, null, null);
        item.setRowValues(rowValues);
        item.setValidationErrors(validationErrors);
        item.setStatus(ImportItem.ImportItemStatus.failed);
        return item;
    }

    private ImportItem skippedItem(ImportBatch batch, int rowNumber, String filename) {
        ImportItem item = failedItem(batch, rowNumber, filename, "{\"title\":null,\"fields\":{}}",
                "[{\"field\":\"reference\",\"code\":\"PATTERN\",\"message\":\"Bad\",\"severity\":\"error\"}]");
        item.setSkippedAtPreview(true);
        return item;
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
        return service(workbook, new ImportExportProperties());
    }

    private BulkImportServiceImpl service(ImportTemplateWorkbook workbook, ImportExportProperties properties) {
        return new BulkImportServiceImpl(importBatchRepository, importItemRepository, documentTypeRepository,
                documentTypeFieldService, workbook, new ImportFilenameMatcher(),
                storageLocationResolver, objectStorageServiceFactory,
                new StagedImportFiles(storageLocationRepository, objectStorageServiceFactory),
                mock(OutboxEventPublisher.class), properties, new ObjectMapper(),
                new FileTypeInspector(new IngestionProperties()), importSchemaFields,
                new ImportRowValidator(new ImportValueConverter(), metadataValidationService,
                        new ImportUsers(userRepository)));
    }

    /** An active Invoice v3 whose template has one optional text field, "reference" (label "Reference"). */
    private void stubActiveTypeAndSchema() {
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.of(documentType(3, DocumentType.DocumentTypeStatus.active)));
        when(importSchemaFields.load(tenantId, documentTypeId, 3)).thenReturn(Map.of("reference",
                new ImportField(UUID.randomUUID(), "reference", "Reference", "text", false, false)));
    }

    /** Every reference value "BAD" fails the type's own metadata rules. */
    private void stubReferenceRule() {
        when(metadataValidationService.validate(eq(documentTypeId), anyInt(), anyMap())).thenAnswer(call -> {
            Map<String, Object> values = call.getArgument(2);
            return "BAD".equals(values.get("reference"))
                    ? List.of(new ValidationError("reference", "PATTERN", "Reference is not valid", "error"))
                    : List.of();
        });
    }

    private void stubBatchSave() {
        when(importBatchRepository.save(any())).thenAnswer(call -> {
            ImportBatch saved = call.getArgument(0);
            saved.setImportBatchId(UUID.randomUUID());
            return saved;
        });
    }

    /**
     * A v3 template with the given data rows, each {filename, title,
     * reference} - a null cell is left blank.
     */
    private byte[] filledTemplate(String[]... rows) {
        byte[] blank = realWorkbook.generate(schema(3), 500);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(blank));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheet(ImportTemplateWorkbook.IMPORT_SHEET);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.getRow(r + 1) != null ? sheet.getRow(r + 1) : sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] != null) {
                        row.createCell(c).setCellValue(rows[r][c]);
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
