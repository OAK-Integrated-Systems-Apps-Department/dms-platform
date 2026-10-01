package com.oak.dms.importexport.enums;

/**
 * Lifecycle of a bulk import batch (migration_batches.status, see
 * ck_migration_batches_status in V42). {@code completed_with_errors}: every
 * row finished and at least one failed.
 */
public enum MigrationBatchStatus {
    pending,
    running,
    completed,
    completed_with_errors,
    failed,
    cancelled;

    public boolean isFinished() {
        return this == completed || this == completed_with_errors || this == failed || this == cancelled;
    }
}
