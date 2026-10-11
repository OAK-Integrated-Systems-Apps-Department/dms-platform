package com.oak.dms.importexport.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Binds the dms.import-export.* configuration keys limiting bulk import
 * intake - see {@code BulkImportServiceImpl} - and sizing its worker, and
 * the bulk export limits, file lifetime and worker sizing.
 */
@Component
@ConfigurationProperties(prefix = "dms.import-export")
@Getter
@Setter
public class ImportExportProperties {

    /** Maximum uploads (each one item, accepted or rejected) in one batch. */
    private int maxFilesPerBatch = 500;

    /** Maximum total size of all files staged for one batch. */
    private DataSize maxBatchSize = DataSize.ofGigabytes(2);

    /** Batches a user may have in uploading/queued/running at once. */
    private int maxActiveBatchesPerUser = 1;

    /**
     * Whether a document type's mandatory fields must all have a usable
     * default value (an import's only metadata). False (the default): such
     * fields are left empty and every row gets a MANDATORY_FIELD_DEFERRED
     * warning naming them, so the documents can be found and backfilled
     * later. True: starting an import into the type is refused with 400,
     * listing the fields.
     */
    private boolean enforceMandatoryFields = false;

    /**
     * Consumers on the bulk import queue - how many batches are validated
     * at once (see {@code BulkImportMessagingConfig}).
     */
    private int workerConcurrency = 1;

    /**
     * How often {@code ImportCompletionScheduler} re-checks rows still
     * waiting on a scan verdict. Read by its {@code @Scheduled}
     * placeholder, which carries the same default; declared here so the
     * key is documented with the rest.
     */
    private long completionIntervalMs = 30000;

    /**
     * How long after a batch finishes {@code RetainedImportFilePurger}
     * waits before deleting any staged file still left on it. The finaliser
     * already deletes them when the batch finishes; this is the backstop
     * for a delete that didn't happen.
     */
    private int retainedFileHours = 24;

    /**
     * How often {@code RetainedImportFilePurger} runs. Read by its
     * {@code @Scheduled} placeholder, which carries the same default;
     * declared here so the key is documented with the rest.
     */
    private long retainedFilePurgeIntervalMs = 3600000;

    /**
     * How long a batch may sit in {@code uploading} with no activity (on the
     * batch or any of its rows) before {@code ImportBatchRecoveryJob}
     * cancels it as expired and deletes its staged files.
     */
    private int abandonedUploadHours = 24;

    /**
     * How long a {@code queued} or {@code running} batch may go with no
     * activity before {@code ImportBatchRecoveryJob} treats it as stuck:
     * the first time its submitted event is published again, the next time
     * its unprocessed rows are failed and it is finalised. Set it above the
     * longest expected wait in the import queue.
     */
    private int stuckBatchHours = 2;

    /**
     * How often {@code ImportBatchRecoveryJob} runs. Read by its
     * {@code @Scheduled} placeholder, which carries the same default;
     * declared here so the key is documented with the rest.
     */
    private long batchRecoveryIntervalMs = 900000;

    // ---- Bulk export - see BulkExportServiceImpl, BulkExportWorker and
    // BulkExportCleanupJob.

    /** Maximum documents in one bulk export. */
    private int maxDocumentsPerExport = 1000;

    /** Maximum total size of the current versions of one export's documents. */
    private DataSize maxExportSize = DataSize.ofGigabytes(2);

    /** Exports a user may have pending or processing at once. */
    private int maxActiveExportsPerUser = 1;

    /** How long a finished export's ZIP can be downloaded before it expires and is deleted. */
    private int exportFileTtlHours = 24;

    /**
     * How long before expiry {@code BulkExportCleanupJob} publishes
     * {@code export_job.expiring_soon} (once per export) for the
     * notification module.
     */
    private int exportExpiryWarningHours = 2;

    /**
     * Consumers on the bulk export queue - how many exports are built at
     * once (see {@code BulkExportMessagingConfig}).
     */
    private int exportWorkerConcurrency = 1;

    /**
     * How often {@code BulkExportCleanupJob} runs. Read by its
     * {@code @Scheduled} placeholder, which carries the same default;
     * declared here so the key is documented with the rest.
     */
    private long exportCleanupIntervalMs = 900000;

    /**
     * How long a pending or processing export may go with no activity
     * before {@code BulkExportCleanupJob} fails it as stuck. Activity, not
     * age: the worker bumps the job's updated_at at every stage and at
     * least every 50 documents, so a long export that is still moving is
     * never failed. Set it above the longest expected wait in the export
     * queue.
     */
    private int stuckExportHours = 2;
}
