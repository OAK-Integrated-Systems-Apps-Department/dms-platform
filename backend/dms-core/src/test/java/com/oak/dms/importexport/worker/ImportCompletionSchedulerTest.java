package com.oak.dms.importexport.worker;

import com.oak.dms.importexport.service.BulkImportAuditor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.document.DocumentService;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.storage.StagedImportFiles;
import com.oak.dms.importexport.validation.ImportField;
import com.oak.dms.importexport.validation.ImportSchemaFields;
import com.oak.dms.importexport.validation.ImportUsers;
import com.oak.dms.importexport.validation.ImportValueConverter;
import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImportCompletionSchedulerTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentTypeId = UUID.randomUUID();
    private final UUID amountFieldId = UUID.randomUUID();

    private final ImportBatchRepository importBatchRepository = mock(ImportBatchRepository.class);
    private final ImportItemRepository importItemRepository = mock(ImportItemRepository.class);
    private final ImportSchemaFields importSchemaFields = mock(ImportSchemaFields.class);
    private final IngestionGate ingestionGate = mock(IngestionGate.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final DocumentMetadataRepository documentMetadataRepository = mock(DocumentMetadataRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final StagedImportFiles stagedImportFiles = new StagedImportFiles(mock(StorageLocationRepository.class),
            mock(ObjectStorageServiceFactory.class));

    private final ImportRecordCreator recordCreator = new ImportRecordCreator(documentService, ingestionGate,
            documentMetadataRepository, importItemRepository, stagedImportFiles, new ImportValueConverter(),
            new ImportUsers(mock(UserRepository.class)), new ObjectMapper(), transactionManager);
    private final ImportBatchFinaliser finaliser = new ImportBatchFinaliser(importBatchRepository,
            importItemRepository, stagedImportFiles, mock(BulkImportAuditor.class), transactionManager);
    private final ImportCompletionScheduler scheduler = new ImportCompletionScheduler(importItemRepository,
            importSchemaFields, ingestionGate, recordCreator, finaliser);

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(documentMetadataRepository.findById(any())).thenReturn(Optional.empty());
        when(importSchemaFields.load(tenantId, documentTypeId, 3)).thenReturn(Map.of(
                "amount", new ImportField(amountFieldId, "amount", "Amount", "decimal", false, false)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void completePendingItems_cleanTicket_resolvesItemAndFinalisesBatch() {
        ImportBatch batch = runningBatch();
        ImportItem item = submittedItem(batch);
        stubPending(batch, item);
        UUID documentId = UUID.randomUUID();
        AtomicReference<UUID> userDuringLookup = new AtomicReference<>();
        when(ingestionGate.getTicket(item.getScanResultId())).thenAnswer(invocation -> {
            userDuringLookup.set(TenantContext.getUserId());
            return ticket(item.getScanResultId(), ScanResultStatus.clean, documentId);
        });

        scheduler.completePendingItems();

        assertThat(userDuringLookup.get()).isEqualTo(userId);
        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.success);
        assertThat(item.getDocumentId()).isEqualTo(documentId);
        ArgumentCaptor<DocumentMetadata> saved = ArgumentCaptor.forClass(DocumentMetadata.class);
        verify(documentMetadataRepository).save(saved.capture());
        assertThat(saved.getValue().getFieldValues()).containsOnlyKeys(amountFieldId.toString());
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.completed);
        assertThat(batch.getSuccessCount()).isEqualTo(1);
        assertThat(batch.getCompletedAt()).isNotNull();
        verifyNoInteractions(documentService);
        assertThatThrownBy(TenantContext::getTenantId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completePendingItems_stillScanning_leavesItemAndBatchRunning() {
        ImportBatch batch = runningBatch();
        ImportItem item = submittedItem(batch);
        stubPending(batch, item);
        when(ingestionGate.getTicket(item.getScanResultId()))
                .thenReturn(ticket(item.getScanResultId(), ScanResultStatus.pending, null));

        scheduler.completePendingItems();

        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.pending);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.running);
        assertThat(batch.getCompletedAt()).isNull();
    }

    @Test
    void completePendingItems_ticketLookupFails_isLoggedAndContextCleared() {
        ImportBatch batch = runningBatch();
        ImportItem item = submittedItem(batch);
        stubPending(batch, item);
        when(ingestionGate.getTicket(item.getScanResultId())).thenThrow(new IllegalStateException("boom"));

        scheduler.completePendingItems();

        assertThat(item.getStatus()).isEqualTo(ImportItemStatus.pending);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.running);
        assertThatThrownBy(TenantContext::getTenantId).isInstanceOf(IllegalStateException.class);
    }

    private void stubPending(ImportBatch batch, ImportItem item) {
        UUID batchId = batch.getImportBatchId();
        when(importItemRepository.findPendingCompletions(any())).thenReturn(List.of(new PendingCompletion(
                item.getImportItemId(), item.getScanResultId(), batchId, tenantId, userId, documentTypeId, 3)));
        when(importItemRepository.lockPendingForCompletion(item.getImportItemId())).thenAnswer(invocation ->
                item.getStatus() == ImportItemStatus.pending ? Optional.of(item) : Optional.empty());
        when(importBatchRepository.lockByImportBatchId(batchId)).thenReturn(Optional.of(batch));
        for (ImportItemStatus status : ImportItemStatus.values()) {
            when(importItemRepository.countByImportBatchIdAndStatus(eq(batchId), eq(status)))
                    .thenAnswer(invocation -> item.getStatus() == status ? 1L : 0L);
        }
        when(importItemRepository.findAllByImportBatchIdAndStagedObjectKeyIsNotNull(batchId)).thenReturn(List.of());
    }

    private ImportBatch runningBatch() {
        return ImportBatch.builder()
                .importBatchId(UUID.randomUUID())
                .tenantId(tenantId)
                .sourceType(ImportBatch.SourceType.bulk_import)
                .documentTypeId(documentTypeId)
                .schemaVersion(3)
                .status(ImportBatchStatus.running)
                .totalRecords(1)
                .correlationId(UUID.randomUUID())
                .createdBy(userId)
                .build();
    }

    private ImportItem submittedItem(ImportBatch batch) {
        return ImportItem.builder()
                .importItemId(UUID.randomUUID())
                .tenantId(tenantId)
                .importBatchId(batch.getImportBatchId())
                .rowNumber(2)
                .filenameReference("a.pdf")
                .rowValues("{\"title\":null,\"fields\":{\"amount\":\"12.50\"}}")
                .scanResultId(UUID.randomUUID())
                .status(ImportItemStatus.pending)
                .build();
    }

    private static IngestionTicketResponse ticket(UUID scanResultId, ScanResultStatus status, UUID documentId) {
        return new IngestionTicketResponse(scanResultId, status, documentId, null, "a.pdf", 10L, null, null, null,
                false, OffsetDateTime.now(), null);
    }
}
