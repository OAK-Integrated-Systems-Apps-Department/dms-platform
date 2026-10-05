package com.oak.dms.importexport.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.ApiException;
import com.oak.dms.document.DocumentService;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.importexport.validation.ImportDefaults;
import com.oak.dms.importexport.validation.ImportField;
import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.entity.DocumentMetadata.FieldValue;
import com.oak.dms.metadata.entity.DocumentMetadataId;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.exception.FileTooLargeException;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImportRecordCreatorTest {

    private static final String WARNING_JSON =
            "[{\"field\":\"amount\",\"code\":\"MAX_VALUE\",\"message\":\"Large\",\"severity\":\"warning\"}]";

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentTypeId = UUID.randomUUID();
    private final UUID amountFieldId = UUID.randomUUID();
    private final UUID noteFieldId = UUID.randomUUID();
    private final BulkImportWorker.ClaimedBatch batch =
            new BulkImportWorker.ClaimedBatch(UUID.randomUUID(), tenantId, userId, documentTypeId, 3);
    private final ImportDefaults.Resolved defaults = new ImportDefaults.Resolved(List.of(
            new ImportDefaults.AppliedDefault(
                    new ImportField(amountFieldId, "amount", "Amount", "decimal", false, false, "12.50"),
                    "12.50", new BigDecimal("12.50")),
            new ImportDefaults.AppliedDefault(
                    new ImportField(noteFieldId, "note", "Note", "text", false, false, "hello"),
                    "hello", "hello")),
            List.of(), List.of());
    private final StorageLocation location = StorageLocation.builder().storageLocationId(UUID.randomUUID()).build();

    private final DocumentService documentService = mock(DocumentService.class);
    private final IngestionGate ingestionGate = mock(IngestionGate.class);
    private final DocumentMetadataRepository documentMetadataRepository = mock(DocumentMetadataRepository.class);
    private final ImportItemRepository importItemRepository = mock(ImportItemRepository.class);
    private final StorageLocationRepository storageLocationRepository = mock(StorageLocationRepository.class);
    private final ObjectStorageServiceFactory objectStorageServiceFactory = mock(ObjectStorageServiceFactory.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ImportRecordCreator creator = new ImportRecordCreator(documentService, ingestionGate,
            documentMetadataRepository, importItemRepository,
            new StagedImportFiles(storageLocationRepository, objectStorageServiceFactory), objectMapper,
            transactionManager);

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(storageLocationRepository.findByStorageLocationIdAndDeletedFalse(location.getStorageLocationId()))
                .thenReturn(Optional.of(location));
        when(objectStorageServiceFactory.resolve(location)).thenReturn(storage);
        when(documentMetadataRepository.findById(any())).thenReturn(Optional.empty());
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void createRecord_cleanTicket_writesDefaultsKeyedByFieldIdWithSrcImportAndMarksSuccess() {
        ImportItem item = stagedItem();
        stub(item);
        UUID documentId = UUID.randomUUID();
        when(documentService.uploadDocument(eq(documentTypeId), eq("Q3 report"), eq("Q3 report.pdf"), eq(1024L),
                any())).thenReturn(ticket(ScanResultStatus.clean, documentId, null, null));

        creator.createRecord(batch, item.getImportItemId(), defaults);

        ArgumentCaptor<DocumentMetadata> saved = ArgumentCaptor.forClass(DocumentMetadata.class);
        verify(documentMetadataRepository).save(saved.capture());
        DocumentMetadata metadata = saved.getValue();
        assertThat(metadata.getId()).isEqualTo(new DocumentMetadataId(tenantId, documentId));
        assertThat(metadata.getDocumentTypeId()).isEqualTo(documentTypeId);
        assertThat(metadata.getSchemaVersion()).isEqualTo(3);
        assertThat(metadata.getCreatedBy()).isEqualTo(userId);
        assertThat(metadata.getFieldValues()).containsOnly(
                Map.entry(amountFieldId.toString(), new FieldValue(new BigDecimal("12.50"), "import", null)),
                Map.entry(noteFieldId.toString(), new FieldValue("hello", "import", null)));

        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.success);
        assertThat(item.getDocumentId()).isEqualTo(documentId);
        assertThat(item.getScanResultId()).isNotNull();
    }

    @Test
    void createRecord_filenameWithFolderPath_submitsBareNameTitledWithoutExtension() {
        ImportItem item = stagedItem();
        item.setFilenameReference("scans\\2026/Q3 report.final.pdf");
        stub(item);
        when(documentService.uploadDocument(any(), any(), any(), anyLong(), any()))
                .thenReturn(ticket(ScanResultStatus.pending, null, null, null));

        creator.createRecord(batch, item.getImportItemId(), defaults);

        verify(documentService).uploadDocument(eq(documentTypeId), eq("Q3 report.final"), eq("Q3 report.final.pdf"),
                eq(1024L), any());
    }

    @Test
    void createRecord_noDefaults_createsTheDocumentWithEmptyMetadata() {
        ImportItem item = stagedItem();
        stub(item);
        UUID documentId = UUID.randomUUID();
        when(documentService.uploadDocument(any(), any(), any(), anyLong(), any()))
                .thenReturn(ticket(ScanResultStatus.clean, documentId, null, null));

        creator.createRecord(batch, item.getImportItemId(),
                new ImportDefaults.Resolved(List.of(), List.of(), List.of()));

        ArgumentCaptor<DocumentMetadata> saved = ArgumentCaptor.forClass(DocumentMetadata.class);
        verify(documentMetadataRepository).save(saved.capture());
        assertThat(saved.getValue().getFieldValues()).isEmpty();
        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.success);
    }

    @Test
    void createRecord_gateAccepts_recordsTicketClearsStagingAndDeletesFileAfterCommit() {
        ImportItem item = stagedItem();
        stub(item);
        InputStream[] handedOver = new InputStream[1];
        IngestionTicketResponse pending = ticket(ScanResultStatus.pending, null, null, null);
        when(documentService.uploadDocument(any(), any(), any(), anyLong(), any())).thenAnswer(invocation -> {
            handedOver[0] = invocation.getArgument(4);
            return pending;
        });

        creator.createRecord(batch, item.getImportItemId(), defaults);

        assertThat(item.getScanResultId()).isEqualTo(pending.scanResultId());
        assertThat(item.getStagedObjectKey()).isNull();
        assertThat(item.getStagedStorageLocationId()).isNull();
        assertThat(item.getStagedSizeBytes()).isNull();
        verify(storage, never()).delete(any(), anyString());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(storage).delete(location, "imports/key-1");
        assertThat(handedOver[0]).isNotNull();
    }

    @Test
    void createRecord_pendingTicket_leavesItemPendingWithScanResultId() {
        ImportItem item = stagedItem();
        stub(item);
        IngestionTicketResponse pending = ticket(ScanResultStatus.pending, null, null, null);
        when(documentService.uploadDocument(any(), any(), any(), anyLong(), any())).thenReturn(pending);

        creator.createRecord(batch, item.getImportItemId(), defaults);

        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.pending);
        assertThat(item.getScanResultId()).isEqualTo(pending.scanResultId());
        assertThat(item.getDocumentId()).isNull();
        verify(documentMetadataRepository, never()).save(any());
    }

    @Test
    void handleTicket_rejectedVerdicts_failItemAndKeepEarlierWarnings() throws Exception {
        Map<ScanResultStatus, String> expected = Map.of(
                ScanResultStatus.infected, "Malware detected: Eicar-Test-Signature",
                ScanResultStatus.quarantined, "Malware detected: Eicar-Test-Signature",
                ScanResultStatus.unscannable, "File could not be scanned: The file is password-protected",
                ScanResultStatus.failed, "File could not be scanned: The file is password-protected");
        for (Map.Entry<ScanResultStatus, String> entry : expected.entrySet()) {
            ImportItem item = submittedItem();
            item.setValidationErrors(WARNING_JSON);
            stub(item);
            boolean malware = entry.getKey() == ScanResultStatus.infected
                    || entry.getKey() == ScanResultStatus.quarantined;
            IngestionTicketResponse ticket = ticket(entry.getKey(), null,
                    malware ? "Eicar-Test-Signature" : null, "The file is password-protected");

            creator.handleTicket(batch, item.getImportItemId(), ticket, defaults);

            assertThat(item.getStatus()).as(entry.getKey().name()).isEqualTo(ImportItemStatus.failed);
            JsonNode errors = objectMapper.readTree(item.getValidationErrors());
            assertThat(errors).hasSize(2);
            assertThat(errors.get(0).get("code").asText()).isEqualTo("MAX_VALUE");
            assertThat(errors.get(1).get("code").asText()).isEqualTo("SCAN_REJECTED");
            assertThat(errors.get(1).get("severity").asText()).isEqualTo("error");
            assertThat(errors.get(1).get("message").asText()).as(entry.getKey().name()).isEqualTo(entry.getValue());
        }
        verify(documentMetadataRepository, never()).save(any());
    }

    @Test
    void createRecord_gateRejectsFile_failsItemWithoutScanResult() throws Exception {
        ImportItem item = stagedItem();
        stub(item);
        when(documentService.uploadDocument(any(), any(), any(), anyLong(), any()))
                .thenThrow(new FileTooLargeException(100));

        creator.createRecord(batch, item.getImportItemId(), defaults);

        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.failed);
        assertThat(item.getScanResultId()).isNull();
        JsonNode error = objectMapper.readTree(item.getValidationErrors()).get(0);
        assertThat(error.get("code").asText()).isEqualTo("SCAN_REJECTED");
        assertThat(error.get("message").asText()).isEqualTo("File exceeds the maximum upload size of 100 bytes");
        // Left for finalisation to clean up.
        assertThat(item.getStagedObjectKey()).isEqualTo("imports/key-1");
    }

    @Test
    void createRecord_gatePlatformFailure_propagatesAndLeavesItemPending() {
        ImportItem item = stagedItem();
        stub(item);
        when(documentService.uploadDocument(any(), any(), any(), anyLong(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Storage unavailable") { });

        assertThatThrownBy(() -> creator.createRecord(batch, item.getImportItemId(), defaults))
                .isInstanceOf(ApiException.class);
        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.pending);
        assertThat(item.getScanResultId()).isNull();
    }

    @Test
    void createRecord_stagedFileMissing_failsItem() throws Exception {
        ImportItem item = stagedItem();
        stub(item);
        when(storage.download(location, "imports/key-1")).thenThrow(new StorageObjectNotFoundException("imports/key-1"));

        creator.createRecord(batch, item.getImportItemId(), defaults);

        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.failed);
        assertThat(objectMapper.readTree(item.getValidationErrors()).get(0).get("code").asText())
                .isEqualTo("FILE_MISSING");
        verifyNoInteractions(documentService);
    }

    @Test
    void createRecord_redelivery_skipsFinishedItemsAndRechecksSubmittedOnesWithoutResubmitting() {
        ImportItem succeeded = stagedItem();
        succeeded.setStatus(ImportItemStatus.success);
        ImportItem failed = stagedItem();
        failed.setStatus(ImportItemStatus.failed);
        ImportItem submitted = submittedItem();
        stub(succeeded);
        stub(failed);
        stub(submitted);
        UUID documentId = UUID.randomUUID();
        when(ingestionGate.getTicket(submitted.getScanResultId()))
                .thenReturn(ticket(ScanResultStatus.clean, documentId, null, null));

        creator.createRecord(batch, succeeded.getImportItemId(), defaults);
        creator.createRecord(batch, failed.getImportItemId(), defaults);
        creator.createRecord(batch, submitted.getImportItemId(), defaults);

        verify(ingestionGate).getTicket(submitted.getScanResultId());
        verifyNoInteractions(documentService);
        assertThat(submitted.getStatus()).isEqualTo(ImportItemStatus.success);
        assertThat(submitted.getDocumentId()).isEqualTo(documentId);
        assertThat(succeeded.getStatus()).isEqualTo(ImportItemStatus.success);
        assertThat(failed.getStatus()).isEqualTo(ImportItemStatus.failed);
    }

    @Test
    void handleTicket_metadataRowAlreadyExists_updatesItInsteadOfInserting() {
        ImportItem item = submittedItem();
        stub(item);
        UUID documentId = UUID.randomUUID();
        UUID otherFieldId = UUID.randomUUID();
        UUID originalCreator = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now().minusMinutes(3);
        Map<String, FieldValue> earlier = new HashMap<>();
        earlier.put(amountFieldId.toString(), new FieldValue(new BigDecimal("1"), "import", null));
        earlier.put(otherFieldId.toString(), new FieldValue("kept", "manual", null));
        DocumentMetadata existing = DocumentMetadata.builder()
                .id(new DocumentMetadataId(tenantId, documentId))
                .documentTypeId(documentTypeId)
                .schemaVersion(3)
                .fieldValues(earlier)
                .createdBy(originalCreator)
                .createdAt(createdAt)
                .build();
        when(documentMetadataRepository.findById(new DocumentMetadataId(tenantId, documentId)))
                .thenReturn(Optional.of(existing));

        creator.handleTicket(batch, item.getImportItemId(), ticket(ScanResultStatus.clean, documentId, null, null),
                defaults);

        verify(documentMetadataRepository).save(existing);
        assertThat(existing.getFieldValues()).containsOnly(
                Map.entry(amountFieldId.toString(), new FieldValue(new BigDecimal("12.50"), "import", null)),
                Map.entry(noteFieldId.toString(), new FieldValue("hello", "import", null)),
                Map.entry(otherFieldId.toString(), new FieldValue("kept", "manual", null)));
        assertThat(existing.getCreatedBy()).isEqualTo(originalCreator);
        assertThat(existing.getCreatedAt()).isEqualTo(createdAt);
        assertThat(existing.getUpdatedBy()).isEqualTo(userId);
        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.success);
    }

    @Test
    void handleTicket_itemLockedElsewhereOrResolved_changesNothing() {
        ImportItem item = submittedItem();
        when(importItemRepository.lockPendingForCompletion(item.getImportItemId())).thenReturn(Optional.empty());

        creator.handleTicket(batch, item.getImportItemId(),
                ticket(ScanResultStatus.clean, UUID.randomUUID(), null, null), defaults);

        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.pending);
        verify(documentMetadataRepository, never()).save(any());
        verify(importItemRepository, never()).save(any());
    }

    /** findById and the SKIP LOCKED claim both see the item as it currently is. */
    private void stub(ImportItem item) {
        when(importItemRepository.findById(item.getImportItemId())).thenReturn(Optional.of(item));
        when(importItemRepository.lockPendingForCompletion(item.getImportItemId())).thenAnswer(invocation ->
                item.getStatus() == ImportItemStatus.pending && item.getScanResultId() != null
                        ? Optional.of(item) : Optional.empty());
        when(storage.download(location, "imports/key-1")).thenAnswer(invocation ->
                new DownloadedObject(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "application/pdf"));
    }

    private ImportItem stagedItem() {
        return ImportItem.builder()
                .importItemId(UUID.randomUUID())
                .tenantId(tenantId)
                .importBatchId(batch.importBatchId())
                .rowNumber(2)
                .filenameReference("Q3 report.pdf")
                .rowValues("{}")
                .stagedObjectKey("imports/key-1")
                .stagedStorageLocationId(location.getStorageLocationId())
                .stagedSizeBytes(1024L)
                .status(ImportItemStatus.pending)
                .build();
    }

    private ImportItem submittedItem() {
        ImportItem item = stagedItem();
        item.setStagedObjectKey(null);
        item.setStagedStorageLocationId(null);
        item.setStagedSizeBytes(null);
        item.setScanResultId(UUID.randomUUID());
        return item;
    }

    private static IngestionTicketResponse ticket(ScanResultStatus status, UUID documentId, String threatName,
                                                  String message) {
        return new IngestionTicketResponse(UUID.randomUUID(), status, documentId, null, "Q3 report.pdf", 1024L, null,
                threatName, message, false, OffsetDateTime.now(), null);
    }
}
