package com.oak.dms.importexport.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditActorType;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.BulkImportSummary;
import com.oak.dms.importexport.dto.ImportAcceptedFile;
import com.oak.dms.importexport.dto.ImportDefaultsResponse;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.dto.ImportFailuresCsvFile;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.dto.ImportRowProblem;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.exception.ActiveImportBatchLimitException;
import com.oak.dms.importexport.exception.ImportBatchNotCancellableException;
import com.oak.dms.importexport.exception.ImportBatchSizeException;
import com.oak.dms.importexport.exception.ImportBatchStateException;
import com.oak.dms.importexport.exception.InactiveDocumentTypeException;
import com.oak.dms.importexport.exception.InvalidImportFileException;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.importexport.validation.ImportDefaults;
import com.oak.dms.importexport.validation.ImportField;
import com.oak.dms.importexport.validation.ImportRowError;
import com.oak.dms.importexport.validation.ImportSchemaFields;
import com.oak.dms.importexport.validation.ImportUsers;
import com.oak.dms.importexport.validation.ImportValueConverter;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.metadata.entity.MetadataFieldOption;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.repository.MetadataFieldOptionRepository;
import com.oak.dms.metadata.service.MetadataValidationService;
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
import com.oak.dms.user.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.oak.dms.importexport.AuditTestSupport.mockAuditService;
import static com.oak.dms.importexport.AuditTestSupport.recorded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
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

    private static final byte[] PDF = "%PDF-1.7\n%test".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP = {'P', 'K', 3, 4, 0, 0, 0, 0};

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentTypeId = UUID.randomUUID();

    private final ImportBatchRepository importBatchRepository = mock(ImportBatchRepository.class);
    private final ImportItemRepository importItemRepository = mock(ImportItemRepository.class);
    private final DocumentTypeRepository documentTypeRepository = mock(DocumentTypeRepository.class);
    private final ObjectStorageServiceFactory objectStorageServiceFactory = mock(ObjectStorageServiceFactory.class);
    private final StorageLocationRepository storageLocationRepository = mock(StorageLocationRepository.class);
    private final StorageLocationResolver storageLocationResolver = mock(StorageLocationResolver.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final ImportSchemaFields importSchemaFields = mock(ImportSchemaFields.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final MetadataValidationService metadataValidationService = mock(MetadataValidationService.class);
    private final MetadataFieldOptionRepository metadataFieldOptionRepository =
            mock(MetadataFieldOptionRepository.class);
    private final OutboxEventPublisher outboxEventPublisher = mock(OutboxEventPublisher.class);
    private final AuditService auditService = mockAuditService();
    private final ImportExportProperties properties = new ImportExportProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StorageLocation staging = StorageLocation.builder()
            .storageLocationId(UUID.randomUUID())
            .purpose(StoragePurpose.staging)
            .build();

    /** Every item handed to importItemRepository.save, in call order. */
    private final List<ImportItem> savedItems = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.set(tenantId, userId);
        when(importItemRepository.save(any())).thenAnswer(call -> {
            ImportItem item = call.getArgument(0);
            if (item.getImportItemId() == null) {
                item.setImportItemId(UUID.randomUUID());
            }
            savedItems.add(item);
            return item;
        });
        when(importBatchRepository.save(any())).thenAnswer(call -> {
            ImportBatch saved = call.getArgument(0);
            if (saved.getImportBatchId() == null) {
                saved.setImportBatchId(UUID.randomUUID());
            }
            return saved;
        });
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // --- document types and defaults ---

    @Test
    void listDocumentTypes_returnsActiveTypesForCallersTenantAsOptions() {
        DocumentType invoice = option("INV", "Invoice", DocumentType.DocumentTypeStatus.active);
        DocumentType retired = option("OLD", "Retired", DocumentType.DocumentTypeStatus.inactive);
        DocumentType contract = option("CON", "Contract", DocumentType.DocumentTypeStatus.active);
        when(documentTypeRepository.findAllByTenantOrderedByDisplayOrder(tenantId))
                .thenReturn(List.of(invoice, retired, contract));

        List<ImportDocumentTypeOption> options = service().listDocumentTypes();

        assertThat(options).containsExactly(
                new ImportDocumentTypeOption(invoice.getDocumentTypeId(), "Invoice", "INV"),
                new ImportDocumentTypeOption(contract.getDocumentTypeId(), "Contract", "CON"));
    }

    @Test
    void getDefaults_listsTheDefaultsAppliedAndMandatoryFieldsWithoutOne() {
        stubActiveType(4);
        when(importSchemaFields.load(tenantId, documentTypeId, 4)).thenReturn(fields(
                field("region", "Region", "text", false, "EMEA"),
                field("amount", "Amount", "number", true, "not a number"),
                field("reference", "Reference", "text", true, null),
                field("notes", "Notes", "text", false, null)));

        ImportDefaultsResponse defaults = service().getDefaults(documentTypeId);

        assertThat(defaults.documentTypeId()).isEqualTo(documentTypeId);
        assertThat(defaults.schemaVersion()).isEqualTo(4);
        assertThat(defaults.defaults())
                .containsExactly(new ImportDefaultsResponse.DefaultValue("region", "Region", "EMEA"));
        // A default that doesn't convert isn't usable, so Amount is missing too.
        assertThat(defaults.mandatoryFieldsWithoutDefault()).containsExactly(
                new ImportDefaultsResponse.Field("amount", "Amount"),
                new ImportDefaultsResponse.Field("reference", "Reference"));
        assertThat(defaults.enforceMandatoryFields()).isFalse();
    }

    @Test
    void getDefaults_listsInvalidDefaultsApartFromTheAppliedOnes() {
        stubActiveType(4);
        ImportField retention = field("retention", "Retention Class", "dropdown", true, "Long-term");
        ImportField classification = field("classification", "Classification", "dropdown", false, "Internal");
        ImportField reference = field("reference", "Reference", "text", false, "R-1");
        when(importSchemaFields.load(tenantId, documentTypeId, 4))
                .thenReturn(fields(retention, classification, reference));
        when(metadataFieldOptionRepository.findAllActiveByFieldsOrdered(anyList(), eq(tenantId))).thenReturn(List.of(
                MetadataFieldOption.builder().fieldId(retention.fieldId()).optionValue("short_term")
                        .optionLabel("Short-Term").build(),
                MetadataFieldOption.builder().fieldId(classification.fieldId()).optionValue("internal")
                        .optionLabel("Internal").build()));
        when(metadataValidationService.validate(eq(documentTypeId), eq(4), anyMap())).thenReturn(List.of(
                new ValidationError("reference", "MIN_LENGTH", "Field 'Reference' must be at least 5 characters.",
                        "error")));

        ImportDefaultsResponse defaults = service().getDefaults(documentTypeId);

        assertThat(defaults.defaults()).containsExactly(
                new ImportDefaultsResponse.DefaultValue("classification", "Classification", "Internal"));
        assertThat(defaults.invalidDefaults()).containsExactly(
                new ImportDefaultsResponse.InvalidDefault("retention", "Retention Class", "Long-term",
                        "'Long-term' isn't one of the field's active options"),
                new ImportDefaultsResponse.InvalidDefault("reference", "Reference", "R-1",
                        "Field 'Reference' must be at least 5 characters"));
        assertThat(defaults.mandatoryFieldsWithoutDefault())
                .containsExactly(new ImportDefaultsResponse.Field("retention", "Retention Class"));
    }

    @Test
    void getDefaults_inactiveOrUnknownType_rejects() {
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.of(documentType(1, DocumentType.DocumentTypeStatus.inactive)));
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service().getDefaults(documentTypeId))
                .isInstanceOf(InactiveDocumentTypeException.class);
        assertThatThrownBy(() -> service().getDefaults(unknown)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(importSchemaFields);
    }

    // --- createBatch ---

    @Test
    void createBatch_activeType_createsEmptyUploadingBatchAtCurrentSchemaVersion() {
        stubActiveType(4);

        BulkImportCreatedResponse response = service().createBatch(documentTypeId);

        ArgumentCaptor<ImportBatch> saved = ArgumentCaptor.forClass(ImportBatch.class);
        verify(importBatchRepository).save(saved.capture());
        ImportBatch batch = saved.getValue();
        assertThat(batch.getTenantId()).isEqualTo(tenantId);
        assertThat(batch.getDocumentTypeId()).isEqualTo(documentTypeId);
        assertThat(batch.getSchemaVersion()).isEqualTo(4);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.uploading);
        assertThat(batch.getTotalRecords()).isZero();
        assertThat(batch.getCreatedBy()).isEqualTo(userId);
        assertThat(batch.getCorrelationId()).isNotNull();
        assertThat(response).isEqualTo(new BulkImportCreatedResponse(batch.getImportBatchId(), documentTypeId, 4,
                500));
        verifyNoInteractions(importItemRepository);
        // Not enforcing, so the defaults aren't even looked at.
        verifyNoInteractions(importSchemaFields);
    }

    @Test
    void createBatch_unknownOrInactiveType_rejectsAndSavesNothing() {
        assertThatThrownBy(() -> service().createBatch(documentTypeId)).isInstanceOf(ResourceNotFoundException.class);

        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.of(documentType(1, DocumentType.DocumentTypeStatus.inactive)));
        assertThatThrownBy(() -> service().createBatch(documentTypeId))
                .isInstanceOf(InactiveDocumentTypeException.class)
                .extracting("code").isEqualTo("DOCUMENT_TYPE_INACTIVE");

        verify(importBatchRepository, never()).save(any());
    }

    @Test
    void createBatch_activeBatchLimitReached_rejectsWith409() {
        stubActiveType(1);
        when(importBatchRepository.countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(eq(tenantId), eq(userId),
                anyList())).thenReturn(1L);

        assertThatThrownBy(() -> service().createBatch(documentTypeId))
                .isInstanceOf(ActiveImportBatchLimitException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        verify(importBatchRepository, never()).save(any());
    }

    @Test
    void createBatch_enforcingMandatoryFields_rejectsTypeWithFieldsLackingAUsableDefault() {
        properties.setEnforceMandatoryFields(true);
        stubActiveType(2);
        when(importSchemaFields.load(tenantId, documentTypeId, 2)).thenReturn(fields(
                field("reference", "Reference", "text", true, null),
                field("owner", "Owner", "user_ref", true, "nobody@oak.lk"),
                field("region", "Region", "text", true, "EMEA")));

        assertThatThrownBy(() -> service().createBatch(documentTypeId))
                .isInstanceOf(ImportBatchSizeException.class)
                .hasFieldOrPropertyWithValue("code", "IMPORT_MANDATORY_FIELDS_WITHOUT_DEFAULT")
                .hasMessageContaining("Reference and Owner")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(importBatchRepository, never()).save(any());
    }

    @Test
    void createBatch_enforcingMandatoryFields_rejectsTypeWhoseMandatoryDefaultIsInvalid() {
        properties.setEnforceMandatoryFields(true);
        stubActiveType(2);
        when(importSchemaFields.load(tenantId, documentTypeId, 2)).thenReturn(fields(
                field("retention", "Retention Class", "dropdown", true, "Long-term")));

        assertThatThrownBy(() -> service().createBatch(documentTypeId))
                .isInstanceOf(ImportBatchSizeException.class)
                .hasFieldOrPropertyWithValue("code", "IMPORT_MANDATORY_FIELDS_WITHOUT_DEFAULT")
                .hasMessageContaining("Retention Class");
        verify(importBatchRepository, never()).save(any());
    }

    @Test
    void createBatch_enforcingMandatoryFields_everyMandatoryFieldHasADefault_creates() {
        properties.setEnforceMandatoryFields(true);
        stubActiveType(2);
        when(importSchemaFields.load(tenantId, documentTypeId, 2)).thenReturn(fields(
                field("region", "Region", "text", true, "EMEA"),
                field("notes", "Notes", "text", false, null)));

        assertThat(service().createBatch(documentTypeId).schemaVersion()).isEqualTo(2);
        verify(importBatchRepository).save(any());
    }

    // --- receiveFile ---

    @Test
    void receiveFile_acceptedFile_becomesTheNextItemAndIsStagedInTheTenantsStagingLocation() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem first = item(batch, 1, "first.pdf", staging.getStorageLocationId(), "imports/key-1");
        stubLockedBatch(batch, List.of(first));
        stubStaging();

        ImportFileReceivedResponse response = service().receiveFile(batch.getImportBatchId(), null, "a.pdf",
                "application/octet-stream", PDF.length, new ByteArrayInputStream(PDF));

        ImportItem created = savedItems.get(0);
        assertThat(created.getImportBatchId()).isEqualTo(batch.getImportBatchId());
        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getRowNumber()).isEqualTo(2);
        assertThat(created.getFilenameReference()).isEqualTo("a.pdf");
        assertThat(created.getRowValues()).isEqualTo("{}");
        assertThat(created.getStatus()).isEqualTo(ImportItem.ImportItemStatus.pending);
        String expectedKey = "imports/" + tenantId + "/" + batch.getImportBatchId() + "/" + created.getImportItemId();
        // The client's Content-Type is ignored; the inspector's type is stored.
        verify(storage).upload(eq(staging), eq(expectedKey), any(), eq((long) PDF.length), eq("application/pdf"));
        verify(storageLocationResolver, never()).resolveForTenant(any());
        assertThat(created.getStagedObjectKey()).isEqualTo(expectedKey);
        assertThat(created.getStagedStorageLocationId()).isEqualTo(staging.getStorageLocationId());
        assertThat(created.getStagedSizeBytes()).isEqualTo(PDF.length);
        assertThat(created.getUploadRejectionCode()).isNull();
        assertThat(batch.getTotalRecords()).isEqualTo(2);
        assertThat(response).isEqualTo(new ImportFileReceivedResponse(2, "a.pdf", 2, 0, 2));
    }

    @Test
    void receiveFile_folderPathInName_isStrippedFromTheItem() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        stubLockedBatch(batch, List.of());
        stubStaging();

        ImportFileReceivedResponse response = service().receiveFile(batch.getImportBatchId(), null,
                "C:\\Users\\jane\\scans/contract.pdf", "application/pdf", PDF.length, new ByteArrayInputStream(PDF));

        assertThat(savedItems.get(0).getFilenameReference()).isEqualTo("contract.pdf");
        assertThat(response.filename()).isEqualTo("contract.pdf");
        assertThat(response.rowNumber()).isEqualTo(1);
    }

    @Test
    void receiveFile_duplicateFilename_isAnotherItemOfItsOwn() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem earlier = item(batch, 1, "a.pdf", staging.getStorageLocationId(), "imports/key-1");
        stubLockedBatch(batch, List.of(earlier));
        stubStaging();

        ImportFileReceivedResponse response = service().receiveFile(batch.getImportBatchId(), null, "A.pdf",
                "application/pdf", PDF.length, new ByteArrayInputStream(PDF));

        assertThat(response.rowNumber()).isEqualTo(2);
        assertThat(savedItems.get(0)).isNotSameAs(earlier);
        assertThat(savedItems.get(0).getFilenameReference()).isEqualTo("A.pdf");
        assertThat(earlier.getStagedObjectKey()).isEqualTo("imports/key-1");
    }

    @Test
    void receiveFile_contentNotMatchingExtension_rejects415AndSavesAFailedItemWithFileRejected() throws Exception {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        stubLockedBatch(batch, List.of(item(batch, 1, "first.pdf", staging.getStorageLocationId(), "k")));

        assertThatThrownBy(() -> service().receiveFile(batch.getImportBatchId(), null, "scans/a.pdf", "application/pdf",
                ZIP.length, new ByteArrayInputStream(ZIP)))
                .isInstanceOf(FileTypeNotAllowedException.class)
                .extracting("status").isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        verifyNoInteractions(storageLocationResolver, objectStorageServiceFactory, storage);
        assertThat(savedItems).hasSize(1);
        ImportItem rejected = savedItems.get(0);
        String message = "The file you uploaded ('a.pdf') was rejected: The file's content does not match its .pdf"
                + " extension";
        assertThat(rejected.getRowNumber()).isEqualTo(2);
        assertThat(rejected.getFilenameReference()).isEqualTo("a.pdf");
        assertThat(rejected.getStatus()).isEqualTo(ImportItem.ImportItemStatus.failed);
        assertThat(rejected.getStagedObjectKey()).isNull();
        assertThat(rejected.getUploadRejectionCode()).isEqualTo("FILE_TYPE_NOT_ALLOWED");
        assertThat(rejected.getUploadRejectionMessage()).isEqualTo(message);
        assertThat(errors(rejected)).containsExactly(ImportRowError.error("file", "FILE_REJECTED", message));
        // A rejected upload still counts toward the batch.
        assertThat(batch.getTotalRecords()).isEqualTo(2);
    }

    @Test
    void receiveFile_emptyFile_rejects415AndSavesAFailedItem() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        stubLockedBatch(batch, List.of());

        assertThatThrownBy(() -> service().receiveFile(batch.getImportBatchId(), null, "Contract.pdf",
                "application/pdf", 0, new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(FileTypeNotAllowedException.class)
                .hasMessage("The uploaded file is empty");

        assertThat(savedItems).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo(ImportItem.ImportItemStatus.failed);
            assertThat(item.getUploadRejectionMessage())
                    .isEqualTo("The file you uploaded ('Contract.pdf') was rejected: The uploaded file is empty");
        });
    }

    @Test
    void receiveFile_passwordProtectedDocx_rejects415AndSavesAFailedItem() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        stubLockedBatch(batch, List.of());
        // Office stores a password-protected .docx as an OLE2 compound file.
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};

        assertThatThrownBy(() -> service().receiveFile(batch.getImportBatchId(), null, "a.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ole2.length,
                new ByteArrayInputStream(ole2)))
                .isInstanceOf(FileTypeNotAllowedException.class)
                .hasMessageContaining("Password-protected");

        verifyNoInteractions(storageLocationResolver, objectStorageServiceFactory, storage);
        assertThat(savedItems).singleElement().satisfies(item -> {
            assertThat(item.getStatus()).isEqualTo(ImportItem.ImportItemStatus.failed);
            assertThat(item.getUploadRejectionCode()).isEqualTo("FILE_TYPE_NOT_ALLOWED");
            assertThat(item.getUploadRejectionMessage())
                    .startsWith("The file you uploaded ('a.docx') was rejected: Password-protected files");
        });
    }

    @Test
    void receiveFile_overBatchSizeLimit_rejects400AndSavesAFailedItem() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem staged = item(batch, 1, "a.pdf", staging.getStorageLocationId(), "imports/key-1");
        stubLockedBatch(batch, List.of(staged));
        properties.setMaxBatchSize(DataSize.ofBytes(1024 + 10));

        assertThatThrownBy(() -> service().receiveFile(batch.getImportBatchId(), null, "b.pdf", "application/pdf",
                PDF.length, new ByteArrayInputStream(PDF)))
                .isInstanceOf(ImportBatchSizeException.class)
                .hasFieldOrPropertyWithValue("code", "IMPORT_BATCH_SIZE_EXCEEDED")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(storageLocationResolver, objectStorageServiceFactory, storage);
        assertThat(savedItems).singleElement().satisfies(item -> {
            assertThat(item.getRowNumber()).isEqualTo(2);
            assertThat(item.getStatus()).isEqualTo(ImportItem.ImportItemStatus.failed);
            assertThat(item.getUploadRejectionCode()).isEqualTo("IMPORT_BATCH_SIZE_EXCEEDED");
            assertThat(item.getUploadRejectionMessage()).startsWith("The file you uploaded ('b.pdf') was rejected:"
                    + " Adding this file would take the import over its");
        });
    }

    @Test
    void receiveFile_maxFilesReached_countingRejectedUploads_rejects400AndSavesNothing() {
        properties.setMaxFilesPerBatch(2);
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem accepted = item(batch, 1, "a.pdf", staging.getStorageLocationId(), "imports/key-1");
        ImportItem rejected = rejectedItem(batch, 2, "b.pdf");
        stubLockedBatch(batch, List.of(accepted, rejected));

        assertThatThrownBy(() -> service().receiveFile(batch.getImportBatchId(), null, "c.pdf", "application/pdf",
                PDF.length, new ByteArrayInputStream(PDF)))
                .isInstanceOf(ImportBatchSizeException.class)
                .hasFieldOrPropertyWithValue("code", "IMPORT_TOO_MANY_FILES")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(savedItems).isEmpty();
        verify(importBatchRepository, never()).save(any());
        verifyNoInteractions(storage);
    }

    @Test
    void receiveFile_noFilename_rejects400AndSavesNothing() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        stubLockedBatch(batch, List.of());

        for (String name : Arrays.asList(null, "  ", "folder/")) {
            assertThatThrownBy(() -> service().receiveFile(batch.getImportBatchId(), null, name, "application/pdf",
                    PDF.length, new ByteArrayInputStream(PDF)))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasFieldOrPropertyWithValue("code", "IMPORT_FILE_NAME_MISSING")
                    .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(savedItems).isEmpty();
    }

    @Test
    void receiveFile_batchAlreadySubmitted_rejects409() {
        ImportBatch batch = batch(ImportBatchStatus.queued);
        stubLockedBatch(batch, List.of());

        assertThatThrownBy(() -> service().receiveFile(batch.getImportBatchId(), null, "a.pdf", "application/pdf",
                PDF.length, new ByteArrayInputStream(PDF)))
                .isInstanceOf(ImportBatchStateException.class);
        assertThat(savedItems).isEmpty();
    }

    @Test
    void receiveFile_rejectionsCommitButEveryOtherFailureRollsBack() throws NoSuchMethodException {
        // The rejected item is saved in the request's own transaction, which
        // is committed rather than rolled back for these two exceptions only.
        TransactionAttribute receive = new AnnotationTransactionAttributeSource().getTransactionAttribute(
                BulkImportServiceImpl.class.getMethod("receiveFile", UUID.class, String.class, String.class,
                        String.class, long.class, InputStream.class), BulkImportServiceImpl.class);
        assertThat(receive).isNotNull();
        assertThat(receive.rollbackOn(new FileTypeNotAllowedException("x"))).isFalse();
        assertThat(receive.rollbackOn(new ImportBatchSizeException("IMPORT_BATCH_SIZE_EXCEEDED", "x"))).isFalse();
        assertThat(receive.rollbackOn(new InvalidImportFileException("IMPORT_FILE_NAME_MISSING", "x"))).isTrue();
        assertThat(receive.rollbackOn(new UncheckedIOException(new IOException("x")))).isTrue();

        // Only on receiveFile: createBatch still rolls back on the same exception.
        TransactionAttribute create = new AnnotationTransactionAttributeSource().getTransactionAttribute(
                BulkImportServiceImpl.class.getMethod("createBatch", UUID.class), BulkImportServiceImpl.class);
        assertThat(create.rollbackOn(new ImportBatchSizeException("IMPORT_MANDATORY_FIELDS_WITHOUT_DEFAULT", "x")))
                .isTrue();
    }

    // --- receiveFile: retries (clientFileId) ---

    @Test
    void receiveFile_retryOfAnAcceptedUpload_returnsTheOriginalResponseAndCreatesAndStagesNothing() {
        // Full batch, so the retry is answered before the file limit is checked.
        properties.setMaxFilesPerBatch(4);
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem accepted = item(batch, 1, "a.pdf", staging.getStorageLocationId(), "imports/key-1");
        ImportItem rejected = rejectedItem(batch, 2, "b.pdf");
        ImportItem retried = item(batch, 3, "c.pdf", staging.getStorageLocationId(), "imports/key-3");
        retried.setClientFileId("file-3");
        ImportItem later = item(batch, 4, "d.pdf", staging.getStorageLocationId(), "imports/key-4");
        stubLockedBatch(batch, List.of(accepted, rejected, retried, later));

        ImportFileReceivedResponse response = service().receiveFile(batch.getImportBatchId(), "file-3", "c.pdf",
                "application/pdf", PDF.length, new ByteArrayInputStream(PDF));

        // What row 3 got at the time: 2 accepted (a.pdf and itself), 1 rejected, 3 in all.
        assertThat(response).isEqualTo(new ImportFileReceivedResponse(3, "c.pdf", 2, 1, 3));
        assertThat(savedItems).isEmpty();
        verify(importBatchRepository, never()).save(any());
        verifyNoInteractions(storageLocationResolver, objectStorageServiceFactory, storage);
        assertThat(batch.getTotalRecords()).isZero();
    }

    @Test
    void receiveFile_retryOfAFileTypeRejection_throwsTheSame415AndMessageAndCreatesNothing() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        stubLockedBatch(batch, List.of());
        FileTypeNotAllowedException first = catchThrowableOfType(() -> service().receiveFile(
                batch.getImportBatchId(), "file-1", "a.pdf", "application/pdf", ZIP.length,
                new ByteArrayInputStream(ZIP)), FileTypeNotAllowedException.class);
        ImportItem rejected = savedItems.get(0);
        assertThat(rejected.getClientFileId()).isEqualTo("file-1");

        savedItems.clear();
        clearInvocations(importBatchRepository);
        stubLockedBatch(batch, List.of(rejected));
        FileTypeNotAllowedException replayed = catchThrowableOfType(() -> service().receiveFile(
                batch.getImportBatchId(), "file-1", "a.pdf", "application/pdf", ZIP.length,
                new ByteArrayInputStream(ZIP)), FileTypeNotAllowedException.class);

        assertThat(replayed).isNotNull();
        assertThat(replayed.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE).isEqualTo(first.getStatus());
        assertThat(replayed.getCode()).isEqualTo(first.getCode());
        assertThat(replayed.getMessage()).isEqualTo(first.getMessage())
                .isEqualTo("The file's content does not match its .pdf extension");
        assertThat(savedItems).isEmpty();
        verify(importBatchRepository, never()).save(any());
    }

    @Test
    void receiveFile_retryOfABatchSizeRejection_throwsTheSame400CodeAndMessageAndCreatesNothing() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem staged = item(batch, 1, "a.pdf", staging.getStorageLocationId(), "imports/key-1");
        stubLockedBatch(batch, List.of(staged));
        properties.setMaxBatchSize(DataSize.ofBytes(1024 + 10));
        ImportBatchSizeException first = catchThrowableOfType(() -> service().receiveFile(
                batch.getImportBatchId(), "file-2", "b.pdf", "application/pdf", PDF.length,
                new ByteArrayInputStream(PDF)), ImportBatchSizeException.class);
        ImportItem rejected = savedItems.get(0);

        savedItems.clear();
        clearInvocations(importBatchRepository);
        // Even with room now, the retry gets the original answer.
        properties.setMaxBatchSize(DataSize.ofGigabytes(2));
        stubLockedBatch(batch, List.of(staged, rejected));
        ImportBatchSizeException replayed = catchThrowableOfType(() -> service().receiveFile(
                batch.getImportBatchId(), "file-2", "b.pdf", "application/pdf", PDF.length,
                new ByteArrayInputStream(PDF)), ImportBatchSizeException.class);

        assertThat(replayed).isNotNull();
        assertThat(replayed.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST).isEqualTo(first.getStatus());
        assertThat(replayed.getCode()).isEqualTo("IMPORT_BATCH_SIZE_EXCEEDED").isEqualTo(first.getCode());
        assertThat(replayed.getMessage()).isEqualTo(first.getMessage());
        assertThat(savedItems).isEmpty();
        verify(importBatchRepository, never()).save(any());
        verifyNoInteractions(storage);
    }

    @Test
    void receiveFile_twoClientFileIdsWithTheSameFilename_areTwoItems() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        stubStaging();
        String firstId = UUID.randomUUID().toString();
        String secondId = "x".repeat(64);

        stubLockedBatch(batch, List.of());
        service().receiveFile(batch.getImportBatchId(), firstId, "a.pdf", "application/pdf", PDF.length,
                new ByteArrayInputStream(PDF));
        ImportItem first = savedItems.get(savedItems.size() - 1);
        stubLockedBatch(batch, List.of(first));
        ImportFileReceivedResponse response = service().receiveFile(batch.getImportBatchId(), secondId, "a.pdf",
                "application/pdf", PDF.length, new ByteArrayInputStream(PDF));
        ImportItem second = savedItems.get(savedItems.size() - 1);

        assertThat(second).isNotSameAs(first);
        assertThat(first.getClientFileId()).isEqualTo(firstId);
        assertThat(second.getClientFileId()).isEqualTo(secondId);
        assertThat(second.getRowNumber()).isEqualTo(2);
        assertThat(response).isEqualTo(new ImportFileReceivedResponse(2, "a.pdf", 2, 0, 2));
        verify(storage, times(2)).upload(any(), anyString(), any(), anyLong(), anyString());
    }

    @Test
    void receiveFile_withoutClientFileId_isAlwaysANewItem() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem earlier = item(batch, 1, "a.pdf", staging.getStorageLocationId(), "imports/key-1");
        stubLockedBatch(batch, List.of(earlier));
        stubStaging();

        ImportFileReceivedResponse response = service().receiveFile(batch.getImportBatchId(), null, "a.pdf",
                "application/pdf", PDF.length, new ByteArrayInputStream(PDF));

        assertThat(response.rowNumber()).isEqualTo(2);
        assertThat(savedItems.get(0)).isNotSameAs(earlier);
        assertThat(savedItems.get(0).getClientFileId()).isNull();
    }

    @Test
    void receiveFile_invalidClientFileId_rejects400BeforeTouchingTheBatch() {
        UUID batchId = UUID.randomUUID();

        for (String id : List.of("", " ", "a b", "<script>", "../x", "x".repeat(65), "ü")) {
            assertThatThrownBy(() -> service().receiveFile(batchId, id, "a.pdf", "application/pdf", PDF.length,
                    new ByteArrayInputStream(PDF)))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasFieldOrPropertyWithValue("code", "IMPORT_CLIENT_FILE_ID_INVALID")
                    .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verifyNoInteractions(importBatchRepository, importItemRepository, storage);
    }

    // --- submit ---

    @Test
    void submit_withAnAcceptedFile_queuesSetsTotalAndRecordsStartedOnceByTheUser() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        batch.setCorrelationId(UUID.randomUUID());
        stubLockedBatch(batch, List.of(
                item(batch, 1, "a.pdf", staging.getStorageLocationId(), "imports/key-1"),
                rejectedItem(batch, 2, "b.exe"),
                item(batch, 3, "c.pdf", staging.getStorageLocationId(), "imports/key-3")));

        service().submit(batch.getImportBatchId());

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.queued);
        assertThat(batch.getTotalRecords()).isEqualTo(3);
        assertThat(batch.getUpdatedBy()).isEqualTo(userId);
        verify(outboxEventPublisher).publish(tenantId, BulkImportServiceImpl.AGGREGATE_TYPE, batch.getImportBatchId(),
                BulkImportServiceImpl.SUBMITTED_EVENT_TYPE, BulkImportServiceImpl.submittedPayload(batch));
        AuditEventBuilder event = onlyUserEvent(AuditActionCatalog.BULK_IMPORT_STARTED, batch);
        assertThat(event.newValue()).asInstanceOf(InstanceOfAssertFactories.MAP).containsExactlyInAnyOrderEntriesOf(
                Map.of("documentTypeId", documentTypeId, "totalRecords", 3, "rejectedFiles", 1L));
    }

    @Test
    void submit_noAcceptedFile_rejects400AndQueuesNothing() {
        for (List<ImportItem> items : List.<List<ImportItem>>of(List.of(),
                List.of(rejectedItem(batch(ImportBatchStatus.uploading), 1, "a.exe")))) {
            ImportBatch batch = batch(ImportBatchStatus.uploading);
            stubLockedBatch(batch, items);

            assertThatThrownBy(() -> service().submit(batch.getImportBatchId()))
                    .isInstanceOf(ImportBatchSizeException.class)
                    .hasFieldOrPropertyWithValue("code", "IMPORT_NO_ACCEPTED_FILES")
                    .hasMessage("Upload at least one file that can be imported before submitting.")
                    .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.uploading);
        }
        verify(importBatchRepository, never()).save(any());
        verifyNoInteractions(outboxEventPublisher);
        assertThat(recorded(auditService)).isEmpty();
    }

    @Test
    void submit_alreadySubmitted_rejectsAndRecordsNothing() {
        ImportBatch batch = batch(ImportBatchStatus.queued);
        stubLockedBatch(batch, List.of());

        assertThatThrownBy(() -> service().submit(batch.getImportBatchId()))
                .isInstanceOf(ImportBatchStateException.class);

        verify(importBatchRepository, never()).save(any());
        assertThat(recorded(auditService)).isEmpty();
    }

    @Test
    void submit_batchOfAnotherUser_notFoundAndRecordsNothing() {
        UUID foreignBatchId = UUID.randomUUID();
        when(importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(foreignBatchId, tenantId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().submit(foreignBatchId)).isInstanceOf(ResourceNotFoundException.class);

        assertThat(recorded(auditService)).isEmpty();
    }

    // --- status ---

    @Test
    void getStatus_countsAcceptedAndRejectedUploads() {
        ImportBatch batch = batch(ImportBatchStatus.running);
        ImportItem accepted = item(batch, 1, "a.pdf", staging.getStorageLocationId(), "imports/key-1");
        ImportItem handedToGate = item(batch, 2, "b.pdf", null, null);
        handedToGate.setScanResultId(UUID.randomUUID());
        ImportItem rejected = rejectedItem(batch, 3, "c.exe");
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batch.getImportBatchId()))
                .thenReturn(List.of(accepted, handedToGate, rejected));

        BulkImportStatusResponse status = service().getStatus(batch.getImportBatchId());

        assertThat(status.totalRecords()).isEqualTo(3);
        assertThat(status.filesReceived()).isEqualTo(2);
        assertThat(status.filesRejected()).isEqualTo(1);
        assertThat(status.rejectedFilenames()).containsExactly("c.exe");
    }

    @Test
    void getActive_returnsCallersActiveBatchWithResumeDetails() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        when(importBatchRepository.findFirstByTenantIdAndCreatedByAndStatusInAndDeletedFalseOrderByCreatedAtDesc(
                tenantId, userId, List.of(ImportBatchStatus.uploading, ImportBatchStatus.queued,
                        ImportBatchStatus.running)))
                .thenReturn(Optional.of(batch));
        ImportItem accepted = item(batch, 1, "scans\\a.pdf", UUID.randomUUID(), "imports/key-1");
        accepted.setClientFileId("client-1");
        when(importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batch.getImportBatchId()))
                .thenReturn(List.of(accepted, rejectedItem(batch, 2, "b.exe"), item(batch, 3, "c.pdf", null, null)));

        Optional<BulkImportStatusResponse> active = service().getActive();

        assertThat(active).hasValueSatisfying(response -> {
            assertThat(response.batchId()).isEqualTo(batch.getImportBatchId());
            assertThat(response.documentTypeId()).isEqualTo(documentTypeId);
            assertThat(response.createdAt()).isEqualTo(batch.getCreatedAt());
            assertThat(response.status()).isEqualTo("uploading");
            assertThat(response.filesReceived()).isEqualTo(2);
            assertThat(response.rejectedFilenames()).containsExactly("b.exe");
            assertThat(response.acceptedFiles()).containsExactly(
                    new ImportAcceptedFile(1, "a.pdf", "client-1"),
                    new ImportAcceptedFile(3, "c.pdf", null));
        });
    }

    @ParameterizedTest
    @EnumSource(value = ImportBatchStatus.class, names = {"queued", "running", "completed", "completed_with_errors",
            "failed", "cancelled"})
    void getStatus_notUploading_acceptedFilesIsEmptySoThePollStaysLight(ImportBatchStatus status) {
        ImportBatch batch = batch(status);
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batch.getImportBatchId()))
                .thenReturn(List.of(item(batch, 1, "a.pdf", null, null), rejectedItem(batch, 2, "b.exe")));

        BulkImportStatusResponse response = service().getStatus(batch.getImportBatchId());

        assertThat(response.acceptedFiles()).isEmpty();
        assertThat(response.filesReceived()).isEqualTo(1);
        assertThat(response.rejectedFilenames()).containsExactly("b.exe");
    }

    @Test
    void getActive_noActiveBatch_returnsEmpty() {
        when(importBatchRepository.findFirstByTenantIdAndCreatedByAndStatusInAndDeletedFalseOrderByCreatedAtDesc(
                any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(service().getActive()).isEmpty();
        verifyNoInteractions(importItemRepository);
    }

    // --- cancel ---

    @Test
    void cancel_uploadingBatch_cancelsClearsStagedColumnsAndDeletesFilesAfterCommit() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        ImportItem staged = item(batch, 1, "a.pdf", staging.getStorageLocationId(), "imports/key-1");
        ImportItem rejected = rejectedItem(batch, 2, "b.exe");
        stubLockedBatch(batch, List.of(staged, rejected));
        stubStagingLookup();

        BulkImportStatusResponse response = inTransaction(() -> service().cancel(batch.getImportBatchId()),
                () -> verifyNoInteractions(storage));

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.cancelled);
        assertThat(batch.getCancelReason()).isEqualTo(ImportBatch.CancelReason.user);
        assertThat(batch.getUpdatedBy()).isEqualTo(userId);
        assertThat(staged.getStagedObjectKey()).isNull();
        assertThat(staged.getStagedStorageLocationId()).isNull();
        assertThat(staged.getStagedSizeBytes()).isNull();
        assertThat(response.status()).isEqualTo("cancelled");
        assertThat(response.cancelReason()).isEqualTo("user");
        assertThat(response.totalRecords()).isEqualTo(2);
        assertThat(response.rejectedFilenames()).containsExactly("b.exe");
        verify(importBatchRepository).save(batch);
        verify(storage).delete(staging, "imports/key-1");
    }

    @Test
    void cancel_queuedBatch_cancels() {
        ImportBatch batch = batch(ImportBatchStatus.queued);
        stubLockedBatch(batch, List.of(item(batch, 1, "a.pdf", null, null)));

        BulkImportStatusResponse response = service().cancel(batch.getImportBatchId());

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.cancelled);
        assertThat(response.cancelReason()).isEqualTo("user");
        verify(importBatchRepository).save(batch);
    }

    @Test
    void cancel_runningBatch_rejectsWith409AndChangesNothing() {
        ImportBatch batch = batch(ImportBatchStatus.running);
        stubLockedBatch(batch, List.of());

        assertThatThrownBy(() -> service().cancel(batch.getImportBatchId()))
                .isInstanceOf(ImportBatchNotCancellableException.class)
                .extracting("code").isEqualTo("IMPORT_BATCH_NOT_CANCELLABLE");

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.running);
        verify(importBatchRepository, never()).save(any());
        verify(importItemRepository, never()).saveAll(anyList());
        verifyNoInteractions(objectStorageServiceFactory);
        assertThat(recorded(auditService)).isEmpty();
    }

    @Test
    void cancel_stagedFileDeleteFails_isLoggedNotRethrown() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        stubLockedBatch(batch, List.of(item(batch, 1, "a.pdf", staging.getStorageLocationId(), "imports/key-1")));
        stubStagingLookup();
        doThrow(new IllegalStateException("storage down")).when(storage).delete(any(), anyString());

        assertThatCode(() -> inTransaction(() -> service().cancel(batch.getImportBatchId()), () -> { }))
                .doesNotThrowAnyException();

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.cancelled);
        verify(storage).delete(staging, "imports/key-1");
    }

    @Test
    void cancel_recordsCancelledOnceByTheUser() {
        ImportBatch batch = batch(ImportBatchStatus.uploading);
        stubLockedBatch(batch, List.of(item(batch, 1, "a.pdf", null, null)));

        service().cancel(batch.getImportBatchId());

        AuditEventBuilder event = onlyUserEvent(AuditActionCatalog.BULK_IMPORT_CANCELLED, batch);
        assertThat(event.newValue()).asInstanceOf(InstanceOfAssertFactories.MAP).containsExactlyInAnyOrderEntriesOf(
                Map.of("cancelReason", "user", "previousStatus", "uploading"));
    }

    // --- recent imports ---

    @Test
    void listBatches_queriesOnlyCallersBatchesInTenant() {
        stubBatchPage(List.of());

        service().listBatches(PageRequest.of(0, 10));

        verify(importBatchRepository).findAllByTenantIdAndCreatedByAndDeletedFalse(eq(tenantId), eq(userId),
                any(Pageable.class));
        verifyNoMoreInteractions(importBatchRepository);
    }

    @Test
    void listBatches_sortsNewestFirstAndPassesPagingThrough() {
        stubBatchPage(List.of());

        service().listBatches(PageRequest.of(3, 10, Sort.by("status").ascending()));

        Pageable sent = capturedListPageable();
        assertThat(sent.getPageNumber()).isEqualTo(3);
        assertThat(sent.getPageSize()).isEqualTo(10);
        // The client's sort is replaced, never merged.
        assertThat(sent.getSort()).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("importBatchId"));
    }

    @Test
    void listBatches_requestedSizeOver50_isCappedAt50() {
        stubBatchPage(List.of());
        BulkImportServiceImpl service = service();

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

        Page<BulkImportSummary> page = service().listBatches(PageRequest.of(0, 10));

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

        List<BulkImportSummary> rows = service().listBatches(PageRequest.of(0, 10)).getContent();

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

        Page<BulkImportSummary> page = service().listBatches(PageRequest.of(2, 10));

        assertThat(page.getNumber()).isEqualTo(2);
        assertThat(page.getSize()).isEqualTo(10);
        assertThat(page.getTotalElements()).isEqualTo(21);
        assertThat(page.isLast()).isTrue();
    }

    @Test
    void listBatches_emptyPage_skipsTypeLookup() {
        stubBatchPage(List.of());

        assertThat(service().listBatches(PageRequest.of(0, 10)).getContent()).isEmpty();
        verifyNoInteractions(documentTypeRepository);
    }

    // --- failures ---

    @Test
    void getFailures_returnsOnlyFailedItemsInUploadOrderTitledAfterTheirFile() {
        ImportBatch batch = batch(ImportBatchStatus.completed_with_errors);
        ImportItem rejected = rejectedItem(batch, 2, "Invoice A.exe");
        ImportItem scanRejected = failedItem(batch, 5, "b.pdf",
                "[{\"field\":\"file\",\"code\":\"SCAN_REJECTED\",\"message\":\"Malware detected: X\","
                        + "\"severity\":\"error\"}]");
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItem.ImportItemStatus.failed)).thenReturn(List.of(rejected, scanRejected));
        when(importSchemaFields.load(tenantId, documentTypeId, 3)).thenReturn(Map.of());

        List<ImportRowFailure> failures = service().getFailures(batch.getImportBatchId());

        assertThat(failures).extracting(ImportRowFailure::rowNumber, ImportRowFailure::filename,
                        ImportRowFailure::title)
                .containsExactly(tuple(2, "Invoice A.exe", "Invoice A"), tuple(5, "b.pdf", "b"));
        assertThat(failures.get(0).problems()).extracting(ImportRowProblem::fieldLabel, ImportRowProblem::code)
                .containsExactly(tuple("File", "FILE_REJECTED"));
        verify(importItemRepository, never()).findAllByImportBatchIdOrderByRowNumberAsc(any());
    }

    @Test
    void getFailures_filenameStoredWithAFolderPath_isShownWithoutIt() {
        ImportBatch batch = batch(ImportBatchStatus.failed);
        ImportItem legacy = failedItem(batch, 1, "scans\\2026\\c.pdf", null);
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItem.ImportItemStatus.failed)).thenReturn(List.of(legacy));
        when(importSchemaFields.load(tenantId, documentTypeId, 3)).thenReturn(Map.of());

        ImportRowFailure failure = service().getFailures(batch.getImportBatchId()).get(0);

        assertThat(failure.filename()).isEqualTo("c.pdf");
        assertThat(failure.title()).isEqualTo("c");
        assertThat(failure.problems()).isEmpty();
    }

    @Test
    void getFailures_mapsFieldLabelsFromBatchSchemaVersionOwnFieldsAndUnknownKeys() {
        ImportBatch batch = batch(ImportBatchStatus.completed_with_errors);
        ImportItem row = failedItem(batch, 3, "c.pdf", """
                [{"field":"reference","code":"MANDATORY_FIELD_MISSING","message":"Reference is required","severity":"error"},
                 {"field":"file","code":"SCAN_REJECTED","message":"Malware found","severity":"error"},
                 {"field":"title","code":"TITLE_TOO_LONG","message":"Title too long","severity":"error"},
                 {"field":null,"code":"RECORD_CREATE_FAILED","message":"Could not create","severity":"error"},
                 {"field":"gone_field","code":"INVALID_VALUE","message":"Not valid","severity":"error"}]
                """);
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItem.ImportItemStatus.failed)).thenReturn(List.of(row));
        when(importSchemaFields.load(tenantId, documentTypeId, 3)).thenReturn(fields(
                field("reference", "Reference", "text", true, null)));

        List<ImportRowProblem> problems = service().getFailures(batch.getImportBatchId()).get(0).problems();

        assertThat(problems).extracting(ImportRowProblem::fieldLabel)
                .containsExactly("Reference", "File", "Title", null, "gone_field");
        assertThat(problems.get(4)).isEqualTo(new ImportRowProblem("gone_field", "gone_field",
                "INVALID_VALUE", "Not valid", "error"));
    }

    @Test
    void getFailures_failedRowWithWarnings_showsOnlyItsErrorsAndLeavesStoredErrorsAlone() {
        ImportBatch batch = batch(ImportBatchStatus.completed_with_errors);
        String stored = """
                [{"field":null,"code":"MANDATORY_FIELD_DEFERRED","message":"Left empty","severity":"warning"},
                 {"field":"file","code":"SCAN_REJECTED","message":"Malware found","severity":"error"},
                 {"field":"ref","code":"DEFAULT_VALUE_INVALID","message":"Not used","severity":"warning"}]
                """;
        ImportItem scanRejected = failedItem(batch, 1, "a.pdf", stored);
        ImportItem onlyErrors = failedItem(batch, 2, "b.pdf", """
                [{"field":"title","code":"TITLE_TOO_LONG","message":"Title too long","severity":"error"}]
                """);
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItem.ImportItemStatus.failed)).thenReturn(List.of(scanRejected, onlyErrors));
        when(importSchemaFields.load(tenantId, documentTypeId, 3)).thenReturn(Map.of());

        List<ImportRowFailure> failures = service().getFailures(batch.getImportBatchId());

        assertThat(failures.get(0).problems()).containsExactly(
                new ImportRowProblem("file", "File", "SCAN_REJECTED", "Malware found", "error"));
        assertThat(failures.get(1).problems()).extracting(ImportRowProblem::code).containsExactly("TITLE_TOO_LONG");
        assertThat(scanRejected.getValidationErrors()).isEqualTo(stored);
        verify(importItemRepository, never()).save(any());
        verify(importItemRepository, never()).saveAll(anyList());
    }

    @Test
    void getFailures_failedRowWithOnlyWarnings_isStillListedWithNoProblems() {
        ImportBatch batch = batch(ImportBatchStatus.failed);
        ImportItem row = failedItem(batch, 4, "d.pdf", """
                [{"field":null,"code":"MANDATORY_FIELD_DEFERRED","message":"Left empty","severity":"warning"}]
                """);
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItem.ImportItemStatus.failed)).thenReturn(List.of(row));
        when(importSchemaFields.load(tenantId, documentTypeId, 3)).thenReturn(Map.of());

        List<ImportRowFailure> failures = service().getFailures(batch.getImportBatchId());

        assertThat(failures).singleElement().satisfies(failure -> {
            assertThat(failure.rowNumber()).isEqualTo(4);
            assertThat(failure.problems()).isEmpty();
        });
    }

    @Test
    void getFailures_batchOfAnotherUserOrTenant_notFoundAndReadsNothingElse() {
        UUID foreignBatchId = UUID.randomUUID();
        when(importBatchRepository.findByImportBatchIdAndTenantIdAndCreatedByAndDeletedFalse(foreignBatchId,
                tenantId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getFailures(foreignBatchId))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(importItemRepository, importSchemaFields);
    }

    @Test
    void getFailures_noFailedRows_returnsEmptyWithoutLoadingSchema() {
        ImportBatch batch = batch(ImportBatchStatus.completed);
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItem.ImportItemStatus.failed)).thenReturn(List.of());

        assertThat(service().getFailures(batch.getImportBatchId())).isEmpty();
        verifyNoInteractions(importSchemaFields);
    }

    // --- failures CSV ---

    @Test
    void exportFailuresCsv_oneLinePerProblem_utf8WithBom_escapedAndFormulaSafe_andAuditedOnce() {
        ImportBatch batch = batch(ImportBatchStatus.completed_with_errors);
        ImportItem formulaName = failedItem(batch, 2, "=HYPERLINK(1+1).pdf", """
                [{"field":"file","code":"SCAN_REJECTED","message":"@SUM(1+1)","severity":"error"}]
                """);
        ImportItem twoProblems = failedItem(batch, 3, "a, b.pdf", """
                [{"field":"reference","code":"TOO_LONG","message":"Value \\"x\\", too long","severity":"error"},
                 {"field":null,"code":"RECORD_CREATE_FAILED","message":"+1 failed","severity":"error"},
                 {"field":null,"code":"MANDATORY_FIELD_DEFERRED","message":"Left empty","severity":"warning"}]
                """);
        ImportItem sinhala = failedItem(batch, 4, "ලංකාව.pdf", """
                [{"field":"file","code":"SCAN_REJECTED","message":"-தவறு","severity":"error"}]
                """);
        ImportItem noProblems = failedItem(batch, 5, "c.pdf", null);
        ImportItem scanRejectedWithWarning = failedItem(batch, 6, "d.pdf", """
                [{"field":"file","code":"SCAN_REJECTED","message":"Malware found","severity":"error"},
                 {"field":"reference","code":"DEFAULT_VALUE_INVALID","message":"Not used","severity":"warning"}]
                """);
        ImportItem onlyWarnings = failedItem(batch, 7, "e.pdf", """
                [{"field":null,"code":"MANDATORY_FIELD_DEFERRED","message":"Left empty","severity":"warning"}]
                """);
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItem.ImportItemStatus.failed)).thenReturn(List.of(formulaName, twoProblems, sinhala, noProblems,
                scanRejectedWithWarning, onlyWarnings));
        when(importSchemaFields.load(tenantId, documentTypeId, 3)).thenReturn(fields(
                field("reference", "Reference", "text", false, null)));
        stubActiveType(3);

        ImportFailuresCsvFile csv = service().exportFailuresCsv(batch.getImportBatchId());

        byte[] content = csv.content();
        assertThat(Arrays.copyOf(content, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        assertThat(new String(content, 3, content.length - 3, StandardCharsets.UTF_8)).isEqualTo("""
                Row,File,Field,Problem,Code
                2,'=HYPERLINK(1+1).pdf,File,'@SUM(1+1),SCAN_REJECTED
                3,"a, b.pdf",Reference,"Value ""x"", too long",TOO_LONG
                3,"a, b.pdf",,'+1 failed,RECORD_CREATE_FAILED
                4,ලංකාව.pdf,File,'-தவறு,SCAN_REJECTED
                5,c.pdf,,,
                6,d.pdf,File,Malware found,SCAN_REJECTED
                7,e.pdf,,,
                """);
        assertThat(csv.filename()).matches("INV-import-failures-\\d{8}-\\d{4}\\.csv");

        AuditEventBuilder event = onlyUserEvent(AuditActionCatalog.BULK_IMPORT_FAILED_ROWS_DOWNLOADED, batch);
        assertThat(event.newValue()).asInstanceOf(InstanceOfAssertFactories.MAP).containsExactlyInAnyOrderEntriesOf(
                Map.of("rowCount", 7, "format", "csv"));
    }

    @Test
    void exportFailuresCsv_noFailuresAndTypeDeleted_headerOnlyNamedImport_andStillAudited() {
        ImportBatch batch = batch(ImportBatchStatus.completed);
        stubOwnBatch(batch);
        when(importItemRepository.findAllByImportBatchIdAndStatusOrderByRowNumberAsc(batch.getImportBatchId(),
                ImportItem.ImportItemStatus.failed)).thenReturn(List.of());
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.empty());

        ImportFailuresCsvFile csv = service().exportFailuresCsv(batch.getImportBatchId());

        assertThat(new String(csv.content(), StandardCharsets.UTF_8)).isEqualTo("﻿Row,File,Field,Problem,Code\n");
        assertThat(csv.filename()).matches("import-import-failures-\\d{8}-\\d{4}\\.csv");
        AuditEventBuilder event = onlyUserEvent(AuditActionCatalog.BULK_IMPORT_FAILED_ROWS_DOWNLOADED, batch);
        assertThat(event.newValue()).asInstanceOf(InstanceOfAssertFactories.MAP).containsExactlyInAnyOrderEntriesOf(
                Map.of("rowCount", 0, "format", "csv"));
    }

    @Test
    void exportFailuresCsv_batchOfAnotherUserOrTenant_notFoundAndNotAudited() {
        UUID foreignBatchId = UUID.randomUUID();
        when(importBatchRepository.findByImportBatchIdAndTenantIdAndCreatedByAndDeletedFalse(foreignBatchId,
                tenantId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().exportFailuresCsv(foreignBatchId))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(importItemRepository, importSchemaFields, documentTypeRepository);
        assertThat(recorded(auditService)).isEmpty();
    }

    @Test
    void exportFailuresCsv_runsInAWritableTransactionSoTheAuditInsertIsAllowed() throws NoSuchMethodException {
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();
        TransactionAttribute export = source.getTransactionAttribute(
                BulkImportServiceImpl.class.getMethod("exportFailuresCsv", UUID.class), BulkImportServiceImpl.class);
        assertThat(export).isNotNull();
        assertThat(export.isReadOnly()).isFalse();
    }

    // --- helpers ---

    private void stubStaging() {
        when(storageLocationResolver.resolveForTenant(tenantId, StoragePurpose.staging)).thenReturn(staging);
        when(objectStorageServiceFactory.resolve(staging)).thenReturn(storage);
        when(storage.upload(eq(staging), anyString(), any(), anyLong(), anyString())).thenAnswer(call ->
                new StoredObjectMetadata(call.getArgument(1), call.<Long>getArgument(3), call.getArgument(4), null,
                        null));
    }

    private void stubStagingLookup() {
        when(storageLocationRepository.findByStorageLocationIdAndDeletedFalse(staging.getStorageLocationId()))
                .thenReturn(Optional.of(staging));
        when(objectStorageServiceFactory.resolve(staging)).thenReturn(storage);
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

    private void stubLockedBatch(ImportBatch batch, List<ImportItem> items) {
        when(importBatchRepository.lockByImportBatchIdAndTenantIdAndCreatedBy(batch.getImportBatchId(), tenantId,
                userId)).thenReturn(Optional.of(batch));
        when(importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batch.getImportBatchId()))
                .thenReturn(items);
    }

    private void stubActiveType(int currentSchemaVersion) {
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, tenantId))
                .thenReturn(Optional.of(documentType(currentSchemaVersion, DocumentType.DocumentTypeStatus.active)));
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

    /** The one audit event recorded, checked as the caller's own action on {@code batch}. */
    private AuditEventBuilder onlyUserEvent(String action, ImportBatch batch) {
        List<AuditEventBuilder> events = recorded(auditService);
        assertThat(events).hasSize(1);
        AuditEventBuilder event = events.get(0);
        assertThat(event.action()).isEqualTo(action);
        assertThat(event.actorType()).isEqualTo(AuditActorType.USER);
        assertThat(event.actorId()).isEqualTo(userId);
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.objectType()).isEqualTo(AuditObjectTypes.IMPORT_BATCH);
        assertThat(event.objectId()).isEqualTo(batch.getImportBatchId());
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        return event;
    }

    private List<ImportRowError> errors(ImportItem item) throws Exception {
        return objectMapper.readValue(item.getValidationErrors(), new TypeReference<>() {
        });
    }

    private ImportBatch batch(ImportBatchStatus status) {
        return ImportBatch.builder()
                .importBatchId(UUID.randomUUID())
                .tenantId(tenantId)
                .sourceType(ImportBatch.SourceType.bulk_import)
                .documentTypeId(documentTypeId)
                .schemaVersion(3)
                .status(status)
                .totalRecords(0)
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
                .rowValues("{}")
                .stagedObjectKey(objectKey)
                .stagedStorageLocationId(locationId)
                .stagedSizeBytes(objectKey == null ? null : 1024L)
                .status(ImportItem.ImportItemStatus.pending)
                .build();
    }

    private ImportItem failedItem(ImportBatch batch, int rowNumber, String filename, String validationErrors) {
        ImportItem item = item(batch, rowNumber, filename, null, null);
        item.setValidationErrors(validationErrors);
        item.setStatus(ImportItem.ImportItemStatus.failed);
        return item;
    }

    private ImportItem rejectedItem(ImportBatch batch, int rowNumber, String filename) {
        String message = "The file you uploaded ('" + filename + "') was rejected: nope";
        ImportItem item = failedItem(batch, rowNumber, filename, "[{\"field\":\"file\",\"code\":\"FILE_REJECTED\","
                + "\"message\":\"" + message + "\",\"severity\":\"error\"}]");
        item.setUploadRejectionCode("FILE_TYPE_NOT_ALLOWED");
        item.setUploadRejectionMessage(message);
        return item;
    }

    private static ImportField field(String key, String label, String dataType, boolean mandatory,
                                     String defaultValue) {
        return new ImportField(UUID.randomUUID(), key, label, dataType, mandatory, false, defaultValue);
    }

    private static Map<String, ImportField> fields(ImportField... fields) {
        Map<String, ImportField> map = new LinkedHashMap<>();
        for (ImportField field : fields) {
            map.put(field.fieldKey(), field);
        }
        return map;
    }

    private BulkImportServiceImpl service() {
        return new BulkImportServiceImpl(importBatchRepository, importItemRepository, documentTypeRepository,
                storageLocationResolver, objectStorageServiceFactory,
                new StagedImportFiles(storageLocationRepository, objectStorageServiceFactory),
                outboxEventPublisher, properties, objectMapper,
                new FileTypeInspector(new IngestionProperties()), importSchemaFields,
                new ImportDefaults(new ImportValueConverter(), new ImportUsers(userRepository),
                        metadataValidationService, metadataFieldOptionRepository), auditService);
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
