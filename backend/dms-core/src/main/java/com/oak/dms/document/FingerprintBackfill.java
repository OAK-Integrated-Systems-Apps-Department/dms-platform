package com.oak.dms.document;

import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Works out the text fingerprint (V67, {@link DocumentFingerprints}) of every
 * stored version that doesn't have one yet - new uploads within a couple of
 * minutes, and everything stored before this existed, a batch at a time. One
 * instance at a time (a Postgres session lock). Only scanned-clean files are
 * read, through the same checks as a download ({@link VersionContent}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FingerprintBackfill {

    /** Arbitrary, fixed: "dms-fingerprint". */
    static final long LOCK_KEY = 0x646d_735f_6670_7274L;
    private static final int BATCH = 50;

    private final JdbcTemplate jdbc;
    private final DocumentVersionRepository versions;
    private final VersionContent versionContent;

    @Scheduled(fixedDelayString = "${dms.documents.fingerprint-interval:PT2M}", initialDelayString = "PT1M")
    public void run() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!lock(connection, "select pg_try_advisory_lock(?)")) {
                return null;
            }
            try {
                for (UUID versionId : jdbc.queryForList("""
                        select version_id from document_versions
                        where fingerprinted_at is null and not is_deleted and purged_at is null
                          and scan_status in ('clean', 'overridden')
                        order by created_at desc limit ?
                        """, UUID.class, BATCH)) {
                    fingerprint(versionId);
                }
            } finally {
                lock(connection, "select pg_advisory_unlock(?)");
            }
            return null;
        });
    }

    void fingerprint(UUID versionId) {
        DocumentVersion version = versions.findById(versionId).orElse(null);
        if (version == null) {
            return;
        }
        Long simhash = null;
        if (DocumentFingerprints.readable(version.getOriginalFilename())
                && version.getSizeBytes() <= DocumentFingerprints.MAX_BYTES) {
            TenantContext.set(version.getTenantId(), version.getCreatedBy() != null ? version.getCreatedBy()
                    : new UUID(0, 0));
            try (InputStream in = versionContent.open(version).content()) {
                simhash = DocumentFingerprints.of(version.getOriginalFilename(), in.readAllBytes()).orElse(null);
            } catch (Exception e) {
                log.debug("Could not fingerprint version {}: {}", versionId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
        jdbc.update("update document_versions set content_simhash = ?, fingerprinted_at = now() where version_id = ?",
                simhash, versionId);
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
