package com.oak.dms.importexport.service;

import com.oak.dms.importexport.entity.MigrationBatch;
import com.oak.dms.importexport.entity.MigrationItem;
import com.oak.dms.importexport.enums.MigrationBatchStatus;
import com.oak.dms.importexport.enums.MigrationItemStatus;
import com.oak.dms.importexport.repository.MigrationBatchRepository;
import com.oak.dms.importexport.repository.MigrationItemRepository;
import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.entity.DocumentMetadataId;
import com.oak.dms.metadata.repository.DocumentMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every state change of a batch's rows, one short transaction each. Each
 * one locks the batch row first, so the worker and the scan reconciler
 * (possibly on different threads) never double-count a row, and a row is
 * only ever moved out of {@code pending} once.
 */
@Component
@RequiredArgsConstructor
public class BulkImportRowProcessor {

    /** document_metadata field_values src for imported values. */
    public static final String SOURCE_IMPORT = "import";

    private final MigrationBatchRepository batchRepository;
    private final MigrationItemRepository itemRepository;
    private final DocumentMetadataRepository documentMetadataRepository;

    @Transactional
    public void markRunning(UUID batchId) {
        batchRepository.lockById(batchId).ifPresent(batch -> {
            if (batch.getStatus() == MigrationBatchStatus.pending) {
                batch.setStatus(MigrationBatchStatus.running);
                batch.setStartedAt(OffsetDateTime.now());
                batch.setUpdatedAt(OffsetDateTime.now());
            }
        });
    }

    @Transactional(readOnly = true)
    public boolean cancelRequested(UUID batchId) {
        return batchRepository.findById(batchId).map(MigrationBatch::isCancelRequested).orElse(true);
    }

    /**
     * The row's document exists: store its metadata and count it. Returns
     * false (and writes nothing) if the row had already been settled.
     *
     * @param valuesByFieldId field id -> value, as document_metadata stores them
     */
    @Transactional
    public boolean completeRow(UUID itemId, UUID documentId, Map<String, Object> valuesByFieldId) {
        MigrationItem item = itemRepository.findById(itemId).orElse(null);
        if (item == null) {
            return false;
        }
        MigrationBatch batch = batchRepository.lockById(item.getMigrationBatchId()).orElseThrow();
        item = itemRepository.findById(itemId).orElseThrow();
        if (item.getStatus() != MigrationItemStatus.pending) {
            return false;
        }
        Map<String, DocumentMetadata.FieldValue> fieldValues = new HashMap<>();
        valuesByFieldId.forEach((fieldId, v) -> fieldValues.put(fieldId,
                new DocumentMetadata.FieldValue(v, SOURCE_IMPORT, null)));
        documentMetadataRepository.save(DocumentMetadata.builder()
                .id(new DocumentMetadataId(batch.getTenantId(), documentId))
                .documentTypeId(batch.getDocumentTypeId())
                .schemaVersion(batch.getSchemaVersion())
                .fieldValues(fieldValues)
                .createdBy(batch.getCreatedBy())
                .build());

        item.setStatus(MigrationItemStatus.success);
        item.setDocumentId(documentId);
        item.setValidationResult(null);
        item.setUpdatedAt(OffsetDateTime.now());
        batch.setSuccessCount(batch.getSuccessCount() + 1);
        batch.setUpdatedAt(OffsetDateTime.now());
        return true;
    }

    /** Returns false if the row had already been settled. */
    @Transactional
    public boolean failRow(UUID itemId, String reason) {
        MigrationItem item = itemRepository.findById(itemId).orElse(null);
        if (item == null) {
            return false;
        }
        MigrationBatch batch = batchRepository.lockById(item.getMigrationBatchId()).orElseThrow();
        item = itemRepository.findById(itemId).orElseThrow();
        if (item.getStatus() != MigrationItemStatus.pending) {
            return false;
        }
        item.setStatus(MigrationItemStatus.failed);
        item.setValidationResult(reason);
        item.setUpdatedAt(OffsetDateTime.now());
        batch.setFailureCount(batch.getFailureCount() + 1);
        batch.setUpdatedAt(OffsetDateTime.now());
        return true;
    }

    /** The row's file was accepted by the scan gate but its scan hasn't finished. */
    @Transactional
    public void markAwaitingScan(UUID itemId, UUID scanResultId) {
        itemRepository.findById(itemId).ifPresent(item -> {
            item.setScanResultId(scanResultId);
            item.setUpdatedAt(OffsetDateTime.now());
        });
    }

    /** Rows never handed to the scan gate become cancelled; rows mid-scan are left to finish. */
    @Transactional
    public void cancelUnstarted(UUID batchId) {
        batchRepository.lockById(batchId).orElseThrow();
        for (MigrationItem item : itemRepository
                .findByMigrationBatchIdAndStatusAndScanResultIdIsNullOrderByRowNumberAsc(batchId, MigrationItemStatus.pending)) {
            item.setStatus(MigrationItemStatus.cancelled);
            item.setValidationResult("Not imported - the import was cancelled");
            item.setUpdatedAt(OffsetDateTime.now());
        }
    }

    /**
     * Once no row is pending, closes the batch: cancelled if a cancel was
     * asked for, else completed or completed_with_errors. Returns the
     * batch only when this call closed it, so it's audited exactly once.
     */
    @Transactional
    public Optional<MigrationBatch> finishIfDone(UUID batchId) {
        MigrationBatch batch = batchRepository.lockById(batchId).orElse(null);
        if (batch == null || batch.getStatus().isFinished()
                || itemRepository.countByMigrationBatchIdAndStatus(batchId, MigrationItemStatus.pending) > 0) {
            return Optional.empty();
        }
        MigrationBatchStatus status;
        if (batch.isCancelRequested()) {
            status = MigrationBatchStatus.cancelled;
        } else if (batch.getFailureCount() > 0) {
            status = MigrationBatchStatus.completed_with_errors;
        } else {
            status = MigrationBatchStatus.completed;
        }
        OffsetDateTime now = OffsetDateTime.now();
        batch.setStatus(status);
        if (batch.getStartedAt() == null) {
            batch.setStartedAt(now);
        }
        batch.setCompletedAt(now);
        batch.setUpdatedAt(now);
        return Optional.of(batch);
    }

    /** The whole batch couldn't run (unreadable files and the like). */
    @Transactional
    public Optional<MigrationBatch> failBatch(UUID batchId, String reason) {
        MigrationBatch batch = batchRepository.lockById(batchId).orElse(null);
        if (batch == null || batch.getStatus().isFinished()) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<MigrationItem> pending = itemRepository.findByMigrationBatchIdAndStatusOrderByRowNumberAsc(batchId,
                MigrationItemStatus.pending);
        for (MigrationItem item : pending) {
            item.setStatus(MigrationItemStatus.failed);
            item.setValidationResult("Not imported: " + reason);
            item.setUpdatedAt(now);
        }
        batch.setFailureCount(batch.getFailureCount() + pending.size());
        batch.setStatus(MigrationBatchStatus.failed);
        batch.setFailureReason(reason.length() > 1000 ? reason.substring(0, 1000) : reason);
        batch.setCompletedAt(now);
        batch.setUpdatedAt(now);
        return Optional.of(batch);
    }
}
