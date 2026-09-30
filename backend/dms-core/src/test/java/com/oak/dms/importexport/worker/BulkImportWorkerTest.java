package com.oak.dms.importexport.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportBatch.ImportBatchStatus;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.entity.ImportItem.ImportItemStatus;
import com.oak.dms.importexport.repository.ImportBatchRepository;
import com.oak.dms.importexport.repository.ImportItemRepository;
import com.oak.dms.importexport.validation.ImportField;
import com.oak.dms.importexport.validation.ImportRowError;
import com.oak.dms.importexport.validation.ImportRowValidator;
import com.oak.dms.importexport.validation.ImportSchemaFields;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BulkImportWorkerTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentTypeId = UUID.randomUUID();

    private final ImportBatchRepository importBatchRepository = mock(ImportBatchRepository.class);
    private final ImportItemRepository importItemRepository = mock(ImportItemRepository.class);
    private final ImportSchemaFields importSchemaFields = mock(ImportSchemaFields.class);
    private final ImportRowValidator rowValidator = mock(ImportRowValidator.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final BulkImportWorker worker = new BulkImportWorker(importBatchRepository, importItemRepository,
            importSchemaFields, rowValidator, objectMapper, transactionManager);

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void parseBatchId_doubleEncodedOutboxPayload_readsInnerObject() throws Exception {
        UUID batchId = UUID.randomUUID();
        String payload = "{\"importBatchId\":\"" + batchId + "\",\"correlationId\":\"" + UUID.randomUUID() + "\"}";
        // What OutboxRelay actually sends: the String payload JSON-encoded again.
        byte[] body = objectMapper.writeValueAsBytes(payload);

        assertThat(worker.parseBatchId(body)).isEqualTo(batchId);
    }

    @Test
    void parseBatchId_plainJsonObject_isAcceptedToo() {
        UUID batchId = UUID.randomUUID();
        byte[] body = ("{\"importBatchId\":\"" + batchId + "\"}").getBytes(StandardCharsets.UTF_8);

        assertThat(worker.parseBatchId(body)).isEqualTo(batchId);
    }

    @Test
    void parseBatchId_malformed_rejectsWithoutRequeue() {
        for (String body : List.of("not json", "{}", "{\"importBatchId\":\"nope\"}", "\"just a string\"")) {
            assertThatThrownBy(() -> worker.parseBatchId(body.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
    }

    @Test
    void claim_queuedBatch_movesToRunningAndSetsStartedAt() {
        ImportBatch batch = batch(ImportBatchStatus.queued);
        when(importBatchRepository.lockByImportBatchId(batch.getImportBatchId())).thenReturn(Optional.of(batch));

        Optional<BulkImportWorker.ClaimedBatch> claimed = worker.claim(batch.getImportBatchId());

        assertThat(claimed).contains(new BulkImportWorker.ClaimedBatch(batch.getImportBatchId(), tenantId, userId,
                documentTypeId, 3));
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.running);
        assertThat(batch.getStartedAt()).isNotNull();
        verify(importBatchRepository).save(batch);
    }

    @Test
    void claim_runningBatch_continuesWithoutRestarting() {
        ImportBatch batch = batch(ImportBatchStatus.running);
        OffsetDateTime startedAt = OffsetDateTime.now().minusMinutes(5);
        batch.setStartedAt(startedAt);
        when(importBatchRepository.lockByImportBatchId(batch.getImportBatchId())).thenReturn(Optional.of(batch));

        assertThat(worker.claim(batch.getImportBatchId())).isPresent();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.running);
        assertThat(batch.getStartedAt()).isEqualTo(startedAt);
    }

    @Test
    void onMessage_cancelledBatch_isSkippedWithoutProcessing() throws Exception {
        for (ImportBatchStatus status : List.of(ImportBatchStatus.cancelled, ImportBatchStatus.completed,
                ImportBatchStatus.failed)) {
            ImportBatch batch = batch(status);
            when(importBatchRepository.lockByImportBatchId(batch.getImportBatchId())).thenReturn(Optional.of(batch));

            worker.onMessage(message(batch.getImportBatchId()));

            assertThat(batch.getStatus()).isEqualTo(status);
        }
        verify(importBatchRepository, never()).save(any());
        verifyNoInteractions(importItemRepository, importSchemaFields, rowValidator);
    }

    @Test
    void onMessage_queuedBatch_savesFailuresAndWarningsAndCountsFailures() throws Exception {
        ImportBatch batch = batch(ImportBatchStatus.queued);
        ImportItem failing = item(batch, 2, "a.pdf");
        ImportItem warned = item(batch, 3, "b.pdf");
        ImportItem clean = item(batch, 4, "c.pdf");
        failing.setStatus(ImportItemStatus.pending);
        clean.setStatus(ImportItemStatus.failed); // failed on an earlier attempt, valid now
        clean.setValidationErrors("[{\"code\":\"OLD\"}]");
        List<ImportItem> items = List.of(failing, warned, clean);
        stubBatch(batch, items);

        ImportRowError error = ImportRowError.error("file", "FILE_MISSING", "No file was uploaded for this row");
        ImportRowError warning = new ImportRowError("amount", "MAX_VALUE", "Large", "warning");
        AtomicReference<UUID> tenantDuringValidation = new AtomicReference<>();
        when(rowValidator.validate(any(), any(), any(), any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            tenantDuringValidation.set(TenantContext.getTenantId());
            ImportItem item = invocation.getArgument(0);
            return item == failing ? List.of(error) : item == warned ? List.of(warning) : List.of();
        });
        when(importItemRepository.countByImportBatchIdAndStatus(batch.getImportBatchId(), ImportItemStatus.failed))
                .thenReturn(1L);

        worker.onMessage(message(batch.getImportBatchId()));

        assertThat(failing.getStatus()).isEqualTo(ImportItemStatus.failed);
        assertThat(objectMapper.readTree(failing.getValidationErrors()))
                .isEqualTo(objectMapper.readTree("[{\"field\":\"file\",\"code\":\"FILE_MISSING\","
                        + "\"message\":\"No file was uploaded for this row\",\"severity\":\"error\"}]"));
        assertThat(warned.getStatus()).isEqualTo(ImportItemStatus.pending);
        assertThat(warned.getValidationErrors()).contains("MAX_VALUE").doesNotContain("FILE_MISSING");
        assertThat(clean.getStatus()).isEqualTo(ImportItemStatus.pending);
        assertThat(clean.getValidationErrors()).isNull();

        assertThat(batch.getFailureCount()).isEqualTo(1);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.running);
        assertThat(tenantDuringValidation.get()).isEqualTo(tenantId);
        assertThatThrownBy(TenantContext::getTenantId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onMessage_largeBatch_validatesInChunksAndSkipsSucceededItems() throws Exception {
        ImportBatch batch = batch(ImportBatchStatus.queued);
        List<ImportItem> items = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            items.add(item(batch, i + 2, "file" + i + ".pdf"));
        }
        items.get(0).setStatus(ImportItemStatus.success);
        stubBatch(batch, items);
        when(rowValidator.validate(any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(List.of());

        worker.onMessage(message(batch.getImportBatchId()));

        verify(importItemRepository, times(3)).findAllByImportBatchIdAndImportItemIdIn(eq(batch.getImportBatchId()), any());
        verify(rowValidator, times(119)).validate(any(), any(), any(), any(), any(), anyInt(), any());
        assertThat(items.get(0).getStatus()).isEqualTo(ImportItemStatus.success);
    }

    @Test
    void onMessage_unexpectedFailure_propagatesAndClearsTenantContext() throws Exception {
        ImportBatch batch = batch(ImportBatchStatus.queued);
        when(importBatchRepository.lockByImportBatchId(batch.getImportBatchId())).thenReturn(Optional.of(batch));
        when(importSchemaFields.load(tenantId, documentTypeId, 3)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> worker.onMessage(message(batch.getImportBatchId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.running);
        assertThatThrownBy(TenantContext::getTenantId).isInstanceOf(IllegalStateException.class);
    }

    private void stubBatch(ImportBatch batch, List<ImportItem> items) {
        when(importBatchRepository.lockByImportBatchId(batch.getImportBatchId())).thenReturn(Optional.of(batch));
        when(importSchemaFields.load(tenantId, documentTypeId, 3))
                .thenReturn(Map.of("amount", new ImportField("amount", "Amount", "decimal", false, false)));
        when(importItemRepository.findAllByImportBatchIdOrderByRowNumberAsc(batch.getImportBatchId()))
                .thenReturn(items);
        when(importItemRepository.findAllByImportBatchIdAndImportItemIdIn(eq(batch.getImportBatchId()), any()))
                .thenAnswer(invocation -> {
                    Collection<UUID> ids = invocation.getArgument(1);
                    return items.stream().filter(i -> ids.contains(i.getImportItemId())).toList();
                });
    }

    private Message message(UUID batchId) throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of("importBatchId", batchId,
                "correlationId", UUID.randomUUID()));
        return new Message(objectMapper.writeValueAsBytes(payload), new MessageProperties());
    }

    private ImportBatch batch(ImportBatchStatus status) {
        return ImportBatch.builder()
                .importBatchId(UUID.randomUUID())
                .tenantId(tenantId)
                .sourceType(ImportBatch.SourceType.bulk_import)
                .documentTypeId(documentTypeId)
                .schemaVersion(3)
                .status(status)
                .totalRecords(3)
                .correlationId(UUID.randomUUID())
                .createdBy(userId)
                .build();
    }

    private ImportItem item(ImportBatch batch, int rowNumber, String filename) {
        return ImportItem.builder()
                .importItemId(UUID.randomUUID())
                .tenantId(tenantId)
                .importBatchId(batch.getImportBatchId())
                .rowNumber(rowNumber)
                .filenameReference(filename)
                .stagedObjectKey("imports/" + filename)
                .rowValues("{\"title\":null,\"fields\":{\"amount\":\"12.50\"}}")
                .build();
    }
}
