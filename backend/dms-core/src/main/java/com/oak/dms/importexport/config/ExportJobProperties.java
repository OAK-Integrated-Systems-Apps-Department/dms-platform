package com.oak.dms.importexport.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Binds {@code dms.export.*}.
 * <p>
 * {@code jobTtl} is how long a generated export is kept before the Phase 4
 * auto-purge removes it (it sets {@code export_jobs.expires_at}). The real
 * value is Phase 0 Key Decision #10, still open - the default here and in
 * application.yml is a PLACEHOLDER, not a business-approved number. Set
 * EXPORT_JOB_TTL (ISO-8601 or Spring duration, e.g. PT24H / 7d) once decided.
 */
@Component
@ConfigurationProperties(prefix = "dms.export")
@Getter
@Setter
public class ExportJobProperties {

    /** PLACEHOLDER pending Key Decision #10. */
    private Duration jobTtl = Duration.ofHours(24);

    /** Most documents one export may hold. */
    private int maxDocuments = 1000;

    /** Largest ZIP one export may produce (its files, before compression). */
    private DataSize maxExportSize = DataSize.ofGigabytes(2);

    /** Exports one user may have pending or processing at once. */
    private int maxActiveJobsPerUser = 2;
}
