package com.oak.dms.importexport.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Binds the dms.import-export.* configuration keys limiting bulk import
 * intake - see {@code BulkImportServiceImpl} - and sizing its worker.
 */
@Component
@ConfigurationProperties(prefix = "dms.import-export")
@Getter
@Setter
public class ImportExportProperties {

    /** Maximum non-empty manifest rows (and so files) in one batch. */
    private int maxFilesPerBatch = 500;

    /** Maximum total size of all files staged for one batch. */
    private DataSize maxBatchSize = DataSize.ofGigabytes(2);

    /** Batches a user may have in uploading/queued/running at once. */
    private int maxActiveBatchesPerUser = 1;

    /**
     * Maximum size of an uploaded template. POI loads the whole workbook
     * into memory, so this is checked before the file is opened.
     */
    private DataSize maxTemplateSize = DataSize.ofMegabytes(5);

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
     * How long, after a batch finishes, the staged files of rows that
     * failed on their data are kept so a resubmit of its failed rows can
     * reuse them. {@code RetainedImportFilePurger} deletes them after that.
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
}
