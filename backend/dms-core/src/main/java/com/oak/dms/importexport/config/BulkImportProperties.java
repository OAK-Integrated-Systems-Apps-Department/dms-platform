package com.oak.dms.importexport.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Binds {@code dms.import.*} - template-based bulk import (Module 2.1).
 * <p>
 * Uploaded templates' files (the ZIP, or loose files) and resumable upload
 * sessions are staged on the dms-core node's local disk under
 * {@code stagingDir} until the batch has run; nothing staged there is ever
 * served - every file still goes through the malware scan gate before it
 * becomes a document.
 */
@Component
@ConfigurationProperties(prefix = "dms.import")
@Getter
@Setter
public class BulkImportProperties {

    /** Local staging directory; defaults to {java.io.tmpdir}/dms-import. */
    private String stagingDir = System.getProperty("java.io.tmpdir") + "/dms-import";

    /** Most data rows one template may hold. */
    private int maxRows = 5000;

    /** Largest ZIP accepted through a resumable upload session (FIE: up to 2 GB). */
    private long maxArchiveBytes = 2L * 1024 * 1024 * 1024;

    /** Largest single chunk of a resumable upload (FIE: chunks under 100 MB). */
    private long maxChunkBytes = 64L * 1024 * 1024;

    /** Most entries a ZIP may hold - guards against archives built to exhaust the server. */
    private int maxArchiveEntries = 20_000;

    /** An unfinished upload session is deleted after this. */
    private Duration sessionTtl = Duration.ofHours(24);

    /**
     * Staged files of a finished batch are kept this long, so its failed rows
     * can be resubmitted without uploading the files again.
     */
    private Duration stagingRetention = Duration.ofHours(24);

    /** Files of one batch sent to the scan gate at the same time. */
    private int concurrency = 2;
}
