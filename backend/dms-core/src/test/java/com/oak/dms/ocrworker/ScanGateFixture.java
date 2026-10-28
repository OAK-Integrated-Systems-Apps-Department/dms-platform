package com.oak.dms.ocrworker;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * The rows a {@code document_versions} insert needs in order to be legal.
 *
 * <h2>Why this exists</h2>
 * V39 added {@code trg_document_versions_require_clean_scan}: a version may
 * only be created if it has a document-purpose storage location AND a clean
 * {@code scan_results} row linked to it with a matching file hash. That is the
 * scan gate, enforced in the database so no code path can go round it - which
 * is exactly the point, and also why tests that used to fabricate a version
 * with two {@code save()} calls can no longer do so.
 *
 * <p>These OCR tests are about what happens to a version AFTER it exists, so
 * they are not trying to bypass the gate - they just need a version that got
 * through it. Rather than drive a real upload (which would pull ClamAV and
 * object storage into a test about an outbox event), this writes the rows
 * the gate (and {@code scan_results}' own triggers/checks) require, with the
 * same values a real promotion would have left.
 *
 * <p>The alternative - relaxing the trigger for tests - was not considered:
 * a guarantee that is switched off in tests is not a guarantee.
 */
final class ScanGateFixture {

    private ScanGateFixture() {
    }

    /**
     * A document-purpose storage location for this tenant.
     *
     * <p>FILE_SYSTEM with a throwaway base path: nothing in these tests reads
     * or writes an object, and the trigger only checks the row's
     * {@code purpose}.
     */
    static UUID documentLocation(JdbcTemplate jdbc, UUID tenantId) {
        return storageLocation(jdbc, tenantId, "document");
    }

    /**
     * A staging-purpose storage location for this tenant.
     *
     * <p>{@code scan_results_check_refs} requires {@code staging_location_id}
     * to point at a location whose purpose is literally {@code staging} - the
     * document location above cannot double as this.
     */
    static UUID stagingLocation(JdbcTemplate jdbc, UUID tenantId) {
        return storageLocation(jdbc, tenantId, "staging");
    }

    private static UUID storageLocation(JdbcTemplate jdbc, UUID tenantId, String purpose) {
        UUID locationId = UUID.randomUUID();
        jdbc.update("""
                insert into storage_locations (storage_location_id, tenant_id, name, provider, purpose,
                                               base_path, status)
                values (?, ?, ?, 'FILE_SYSTEM', ?, ?, 'active')
                """, locationId, tenantId, "ocr-it-" + purpose + "-" + locationId, purpose,
                "/tmp/ocr-it/" + purpose + "/" + locationId);
        return locationId;
    }

    /**
     * A clean scan result linked to {@code versionId}, with {@code fileHash}
     * matching the version's - both of which {@code trg_document_versions_
     * require_clean_scan} checks.
     *
     * <p>Must be inserted BEFORE the version in the same transaction, or in an
     * earlier one: the trigger is {@code DEFERRABLE INITIALLY DEFERRED}, so it
     * fires at commit, but the row has to be there by then either way.
     *
     * @param documentTypeId the version's document type - ck_scan_results_intent
     *                       requires it (and a title) for ingestion_kind='new_document'.
     */
    static UUID cleanScanResult(JdbcTemplate jdbc, UUID tenantId, UUID versionId, UUID documentTypeId,
                                UUID uploadedBy, String originalFilename, String mimeType, String fileHash) {
        UUID scanResultId = UUID.randomUUID();
        UUID engineId = scanEngine(jdbc, tenantId);
        UUID stagingLocationId = stagingLocation(jdbc, tenantId);
        jdbc.update("""
                insert into scan_results (scan_result_id, tenant_id, scan_engine_id, staging_location_id,
                                          original_filename, mime_type, scan_mode, uploaded_by, ingestion_kind,
                                          created_reason, status, document_version_id, file_hash, engine_name,
                                          document_type_id, title, started_at, completed_at, created_by)
                values (?, ?, ?, ?, ?, ?, 'pre_store_stream', ?, 'new_document', 'initial_upload', 'clean', ?, ?,
                        'clamav', ?, ?, now(), now(), ?)
                """, scanResultId, tenantId, engineId, stagingLocationId, originalFilename, mimeType,
                uploadedBy, versionId, fileHash, documentTypeId, originalFilename, uploadedBy);
        return scanResultId;
    }

    /** A scan engine row for this tenant - scan_results.scan_engine_id is a real FK. */
    private static UUID scanEngine(JdbcTemplate jdbc, UUID tenantId) {
        UUID engineId = UUID.randomUUID();
        jdbc.update("""
                insert into scan_engines (scan_engine_id, tenant_id, name, provider, scan_mode,
                                          timeout_seconds, max_file_size_bytes)
                values (?, ?, ?, 'CLAMAV', 'pre_store_stream', 60, 104857600)
                """, engineId, tenantId, "ocr-it-engine-" + engineId);
        return engineId;
    }
}
