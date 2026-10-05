package com.oak.dms.importexport.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.ApiException;
import com.oak.dms.document.DocumentService;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.importexport.validation.ImportField;
import com.oak.dms.importexport.validation.ImportUsers;
import com.oak.dms.importexport.validation.ImportValueConverter;
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
import com.oak.dms.user.UserRepository;
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
import static org.mockito.ArgumentMatchers.isNull;
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
    private final Map<String, ImportField> fields = Map.of(
            "amount", new ImportField(amountFieldId, "amount", "Amount", "decimal", false, false),
            "note", new ImportField(noteFieldId, "note", "Note", "text", false, false));
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
    private final com.oak.dms.folder.FolderAccessService folderAccess = mock(com.oak.dms.folder.FolderAccessService.class);
    private final com.oak.dms.document.DuplicateFinder duplicates = mock(com.oak.dms.document.DuplicateFinder.class);
    private final com.oak.dms.document.DocumentVersionService versions =
            mock(com.oak.dms.document.DocumentVersionService.class);

    private final ImportRecordCreator creator = new ImportRecordCreator(documentService, ingestionGate,
            documentMetadataRepository, importItemRepository,
            new StagedImportFiles(storageLocationRepository, objectStorageServiceFactory),
            new ImportValueConverter(), new ImportUsers(mock(UserRepository.class)), objectMapper, transactionManager,
            folderAccess, duplicates, versions, mock(com.oak.dms.document.DocumentRepository.class));

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
    void createRecord_cleanTicket_writesMetadataKeyedByFieldIdAndMarksSuccess() {
        ImportItem item = stagedItem();
        stub(item);
        UUID documentId = UUID.randomUUID();
        when(documentService.uploadDocumentFromJob(eq(documentTypeId), eq("Q3 report"), eq("Q3 report.pdf"), eq(1024L),
                any(), isNull())).thenReturn(ticket(ScanResultStatus.clean, documentId, null, null));

        creator.createRecord(batch, item.getImportItemId(), fields);

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
    void createRecord_gateAccepts_recordsTicketClearsStagingAndDeletesFileAfterCommit() {
        ImportItem item = stagedItem();
        stub(item);
        InputStream[] handedOver = new InputStream[1];
        IngestionTicketResponse pending = ticket(ScanResultStatus.pending, null, null, null);
        when(documentService.uploadDocumentFromJob(any(), any(), any(), anyLong(), any(), any())).thenAnswer(invocation -> {
            handedOver[0] = invocation.getArgument(4);
            return pending;
        });

        creator.createRecord(batch, item.getImportItemId(), fields);

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
        when(documentService.uploadDocumentFromJob(any(), any(), any(), anyLong(), any(), any())).thenReturn(pending);

        creator.createRecord(batch, item.getImportItemId(), fields);

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

            creator.handleTicket(batch, item.getImportItemId(), ticket, fields);

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
    void createRecord_creatorLostEditAccessToTheFolder_failsTheRowWithoutUploadingIt() throws Exception {
        UUID folderId = UUID.randomUUID();
        BulkImportWorker.ClaimedBatch intoFolder = new BulkImportWorker.ClaimedBatch(batch.importBatchId(),
                tenantId, userId, documentTypeId, 3, folderId);
        ImportItem item = stagedItem();
        stub(item);
        // Checked when the batch was created; taken away while it ran.
        when(folderAccess.require(folderId, com.oak.dms.folder.FolderAccessLevel.edit))
                .thenThrow(new com.oak.dms.folder.FolderException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "FOLDER_ACCESS_DENIED", "no"));

        creator.createRecord(intoFolder, item.getImportItemId(), fields);

        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.failed);
        JsonNode error = objectMapper.readTree(item.getValidationErrors()).get(0);
        assertThat(error.get("code").asText()).isEqualTo("ACCESS_REVOKED");
        assertThat(error.get("message").asText()).contains("no longer have edit access");
        verify(documentService, never()).uploadDocumentFromJob(any(), any(), any(), anyLong(), any(), any());
    }

    private BulkImportWorker.ClaimedBatch batchThat(String duplicateAction) {
        return new BulkImportWorker.ClaimedBatch(batch.importBatchId(), tenantId, userId, documentTypeId, 3, null,
                duplicateAction);
    }

    private static com.oak.dms.document.DuplicateFinder.Match match(boolean sameContent, boolean identical,
                                                                    boolean sameName, boolean canEdit) {
        return new com.oak.dms.document.DuplicateFinder.Match(UUID.randomUUID(), "Contract", "contract.pdf", null,
                null, UUID.randomUUID(), sameContent, identical, sameName, sameContent ? "1.0" : null, canEdit);
    }

    @Test
    void duplicates_skip_failsTheRowWithoutUploading_andSaysWhereItIs() throws Exception {
        ImportItem item = stagedItem();
        stub(item);
        when(duplicates.find(any(), any(), any())).thenReturn(List.of(match(true, true, true, true)));

        creator.createRecord(batchThat("skip"), item.getImportItemId(), fields);

        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.failed);
        JsonNode error = objectMapper.readTree(item.getValidationErrors()).get(0);
        assertThat(error.get("code").asText()).isEqualTo("DUPLICATE_SKIPPED");
        assertThat(error.get("message").asText()).contains("already in the DMS as 'Contract'");
        verify(documentService, never()).uploadDocumentFromJob(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void duplicates_newVersion_ofAnIdenticalFile_linksTheExistingDocument_withoutUploading() throws Exception {
        ImportItem item = stagedItem();
        stub(item);
        var existing = match(true, true, true, true);
        when(duplicates.find(any(), any(), any())).thenReturn(List.of(existing));

        creator.createRecord(batchThat("new_version"), item.getImportItemId(), fields);

        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.success);
        assertThat(item.getDocumentId()).isEqualTo(existing.documentId());
        assertThat(objectMapper.readTree(item.getValidationErrors()).get(0).get("message").asText())
                .contains("Identical to 'Contract'");
        verify(documentService, never()).uploadDocumentFromJob(any(), any(), any(), anyLong(), any(), any());
        verify(versions, never()).upload(any(), any(), anyLong(), any(), any());
    }

    @Test
    void duplicates_newVersion_ofAChangedFileWithTheSameName_addsAVersionToIt() {
        ImportItem item = stagedItem();
        stub(item);
        var existing = match(false, false, true, true);
        when(duplicates.find(any(), any(), any())).thenReturn(List.of(existing));
        when(versions.upload(any(), any(), anyLong(), any(), any())).thenThrow(new RuntimeException("stop here"));

        assertThatThrownBy(() -> creator.createRecord(batchThat("new_version"), item.getImportItemId(), fields))
                .hasMessageContaining("stop here");

        verify(versions).upload(eq(existing.documentId()), eq("Q3 report.pdf"), anyLong(), any(), any());
        verify(documentService, never()).uploadDocumentFromJob(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void duplicates_keepBoth_doesntEvenLook() {
        ImportItem item = stagedItem();
        stub(item);
        when(documentService.uploadDocumentFromJob(any(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new RuntimeException("stop here"));

        assertThatThrownBy(() -> creator.createRecord(batchThat("keep_both"), item.getImportItemId(), fields))
                .hasMessageContaining("stop here");

        verifyNoInteractions(duplicates);
    }

    @Test
    void createRecord_folderDeletedMeanwhile_failsTheRow() throws Exception {
        UUID folderId = UUID.randomUUID();
        BulkImportWorker.ClaimedBatch intoFolder = new BulkImportWorker.ClaimedBatch(batch.importBatchId(),
                tenantId, userId, documentTypeId, 3, folderId);
        ImportItem item = stagedItem();
        stub(item);
        when(folderAccess.require(folderId, com.oak.dms.folder.FolderAccessLevel.edit))
                .thenThrow(new com.oak.dms.common.exception.ResourceNotFoundException("Folder", folderId));

        creator.createRecord(intoFolder, item.getImportItemId(), fields);

        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.failed);
        assertThat(objectMapper.readTree(item.getValidationErrors()).get(0).get("message").asText())
                .contains("target folder was deleted");
        verify(documentService, never()).uploadDocumentFromJob(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void createRecord_gateRejectsFile_failsItemWithoutScanResult() throws Exception {
        ImportItem item = stagedItem();
        stub(item);
        when(documentService.uploadDocumentFromJob(any(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new FileTooLargeException(100));

        creator.createRecord(batch, item.getImportItemId(), fields);

        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.failed);
        assertThat(item.getScanResultId()).isNull();
        JsonNode error = objectMapper.readTree(item.getValidationErrors()).get(0);
        assertThat(error.get("code").asText()).isEqualTo("SCAN_REJECTED");
        assertThat(error.get("message").asText()).isEqualTo("This file is larger than the 100 bytes upload limit, so it can't be uploaded.");
        // Left for finalisation to clean up.
        assertThat(item.getStagedObjectKey()).isEqualTo("imports/key-1");
    }

    @Test
    void createRecord_gatePlatformFailure_propagatesAndLeavesItemPending() {
        ImportItem item = stagedItem();
        stub(item);
        when(documentService.uploadDocumentFromJob(any(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Storage unavailable") { });

        assertThatThrownBy(() -> creator.createRecord(batch, item.getImportItemId(), fields))
                .isInstanceOf(ApiException.class);
        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.pending);
        assertThat(item.getScanResultId()).isNull();
    }

    @Test
    void createRecord_stagedFileMissing_failsItem() throws Exception {
        ImportItem item = stagedItem();
        stub(item);
        when(storage.download(location, "imports/key-1")).thenThrow(new StorageObjectNotFoundException("imports/key-1"));

        creator.createRecord(batch, item.getImportItemId(), fields);

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

        creator.createRecord(batch, succeeded.getImportItemId(), fields);
        creator.createRecord(batch, failed.getImportItemId(), fields);
        creator.createRecord(batch, submitted.getImportItemId(), fields);

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
                fields);

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
                ticket(ScanResultStatus.clean, UUID.randomUUID(), null, null), fields);

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
                .rowValues("{\"title\":null,\"fields\":{\"amount\":\"12.50\",\"note\":\"hello\",\"gone\":\"x\"}}")
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
