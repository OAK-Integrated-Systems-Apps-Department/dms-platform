package com.oak.dms.importexport.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

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
    private DataSize maxBatchSize = DataSize.ofGigabytes(20);

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
     * Largest ZIP accepted for one batch, sent directly or through a
     * resumable upload session. Its entries still count toward
     * {@link #maxBatchSize} as they're staged.
     */
    private DataSize maxArchiveSize = DataSize.ofGigabytes(20);

    /** Most entries one ZIP may hold - an archive built to exhaust the server is refused up front. */
    private int maxArchiveEntries = 5000;

    /** Largest single chunk of a resumable upload (kept well under the proxy/multipart limits). */
    private DataSize maxChunkSize = DataSize.ofMegabytes(64);

    /** An unfinished resumable upload is deleted after this. */
    private Duration uploadSessionTtl = Duration.ofHours(24);

    /**
     * Local directory holding resumable uploads while they arrive. Only the
     * node that received a session's chunks has its file, so a multi-node
     * deployment needs sticky routing (or a shared volume) for
     * /api/v1/upload-sessions.
     */
    private String uploadSessionDir = System.getProperty("java.io.tmpdir") + "/dms-upload-sessions";
}
