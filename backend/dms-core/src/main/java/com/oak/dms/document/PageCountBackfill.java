package com.oak.dms.document;

import com.oak.dms.common.io.TempFiles;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Works out how many pages every stored version has (V71, {@link PageCounter}) - new uploads
 * within a couple of minutes, and everything stored before this existed, a batch at a time. One
 * instance at a time (a Postgres session lock). Only scanned-clean files are read, through the
 * same checks as a download ({@link VersionContent}), into a private temporary file that's
 * deleted straight after. Formats without pages are marked {@code none} without being read.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageCountBackfill {

    /** Arbitrary, fixed: "dms-pages". */
    static final long LOCK_KEY = 0x646d_735f_7061_6765L;
    private static final int BATCH = 50;

    private final JdbcTemplate jdbc;
    private final DocumentVersionRepository versions;
    private final VersionContent versionContent;

    /** Files larger than this aren't read for pages (they're marked none). */
    @Value("${dms.impact.page-count-max-bytes:524288000}")
    private long maxBytes;

    @Scheduled(fixedDelayString = "${dms.impact.page-count-interval:PT2M}", initialDelayString = "PT90S")
    public void run() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!lock(connection, "select pg_try_advisory_lock(?)")) {
                return null;
            }
            try {
                for (UUID versionId : jdbc.queryForList("""
                        select version_id from document_versions
                        where pages_counted_at is null and not is_deleted and purged_at is null
                          and scan_status in ('clean', 'overridden')
                        order by created_at desc limit ?
                        """, UUID.class, BATCH)) {
                    count(versionId);
                }
            } finally {
                lock(connection, "select pg_advisory_unlock(?)");
            }
            return null;
        });
    }

    void count(UUID versionId) {
        DocumentVersion version = versions.findById(versionId).orElse(null);
        if (version == null) {
            return;
        }
        Optional<PageCounter.Pages> pages = Optional.empty();
        if (PageCounter.paged(version.getOriginalFilename()) && version.getSizeBytes() <= maxBytes) {
            TenantContext.set(version.getTenantId(), version.getCreatedBy() != null ? version.getCreatedBy()
                    : new UUID(0, 0));
            Path copy = null;
            try (InputStream in = versionContent.open(version).content()) {
                copy = TempFiles.create("dms-pages-", "." + PageCounter.extension(version.getOriginalFilename()));
                Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
                pages = PageCounter.count(version.getOriginalFilename(), copy.toFile());
            } catch (Exception e) {
                log.debug("Could not count the pages of version {}: {}", versionId, e.getMessage());
            } finally {
                TenantContext.clear();
                if (copy != null) {
                    try {
                        Files.deleteIfExists(copy);
                    } catch (Exception ignored) {
                        // the OS cleans up its temp directory
                    }
                }
            }
        }
        jdbc.update("update document_versions set page_count = ?, page_count_source = ?, pages_counted_at = now() "
                        + "where version_id = ?",
                pages.map(PageCounter.Pages::count).orElse(null),
                pages.map(p -> p.source().name().toLowerCase(Locale.ROOT)).orElse("none"),
                versionId);
    }

    private static boolean lock(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
