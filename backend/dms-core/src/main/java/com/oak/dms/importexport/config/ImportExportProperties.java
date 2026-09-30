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
}
