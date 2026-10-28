package com.oak.dms.importexport.worker;

import java.util.UUID;

/**
 * An import item waiting on its scan verdict, with the batch details the
 * completion job needs to resolve it - see
 * {@code ImportItemRepository#findPendingCompletions}.
 */
public record PendingCompletion(UUID importItemId, UUID scanResultId, UUID importBatchId, UUID tenantId,
                                UUID createdBy, UUID documentTypeId, int schemaVersion) {

    BulkImportWorker.ClaimedBatch batch() {
        return new BulkImportWorker.ClaimedBatch(importBatchId, tenantId, createdBy, documentTypeId, schemaVersion);
    }
}
