package com.oak.dms.scanning;

import com.oak.dms.scanning.helper.IngestionProperties;
import com.oak.dms.scanning.helper.ScanProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DOC-03: the configuration as shipped. Uploading a file of any size is a product requirement, so the
 * application-level size limits stay off - the servlet multipart limits, the scan engine's per-file cap - while
 * the only new defaults are limits on the <em>contents of a ZIP</em> (clamd's own MaxFiles and MaxScanSize), which
 * are properties of {@link IngestionProperties} and are applied by the ZIP check alone.
 * <p>
 * Reads the real defaults (no overrides); needs only the database, like any other IT.
 */
@SpringBootTest
class IngestionLimitDefaultsIT {

    @Autowired private IngestionProperties ingestion;
    @Autowired private ScanProperties scan;
    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void zipContentLimits_defaultToWhatClamdOpensOfOneArchive() {
        assertThat(ingestion.getZipMaxEntries()).isEqualTo(10_000);                    // clamd MaxFiles
        assertThat(ingestion.getZipMaxUncompressedBytes()).isEqualTo(4_194_304_000L); // clamd MaxScanSize 4000M
        assertThat(ingestion.getZipMaxEntries()).isEqualTo(scan.getClamav().getArchiveScanFiles());
        assertThat(ingestion.getZipMaxUncompressedBytes()).isEqualTo(scan.getClamav().getArchiveScanBytes());
    }

    @Test
    void theUploadSizeIsStillUnlimited_atEveryApplicationLayer() {
        // servlet multipart: -1 = no limit
        assertThat(environment.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("-1");
        assertThat(environment.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("-1");
        // the scan engine's per-file cap, from configuration and as stored for the platform default engine
        assertThat(scan.getMaxFileSizeBytes()).isEqualTo(Long.MAX_VALUE);
        List<Long> stored = jdbc.queryForList(
                "select max_file_size_bytes from scan_engines where tenant_id is null and is_deleted = false",
                Long.class);
        assertThat(stored).isNotEmpty().allMatch(size -> size == Long.MAX_VALUE);
    }
}
