package com.oak.dms.document;

import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.UUID;

/**
 * Writes the artifacts ocr-worker produces - the OCR output JSON and the
 * searchable PDF - through the same storage layer as everything else.
 *
 * <h2>Why dms-core writes them, not the worker</h2>
 * The worker used to put these in its own MinIO bucket. Two problems with
 * that, and the second is the one that matters: the bucket was outside the
 * encryption layer, so OCR output - which is the document's text, i.e. the
 * document - sat in plaintext next to an encrypted original; and the keys
 * were the worker's to choose, so nothing in dms-core could find, delete or
 * shred them. Both are fixed by making dms-core own the write: the artifacts
 * are encrypted like any other object, keyed under the document they belong
 * to, and reachable by the purge and key-shredding paths.
 *
 * <h2>Idempotent by construction</h2>
 * The key is derived from the version id, not generated, so a retried job
 * overwrites its own artifact rather than leaving a second orphan behind. The
 * row upserts on the same basis. A worker that retries three times leaves one
 * object and one row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcrArtifactWriter {

    private final StorageLocationResolver storageLocationResolver;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final JdbcTemplate jdbc;

    /** What an artifact is, which decides its key prefix and its row. */
    public enum Artifact {
        /** The OCR result JSON - ocr_outputs. */
        output,
        /** The searchable PDF (production prompt 3.11) - renditions, type searchable_pdf. */
        searchable_pdf
    }

    /**
     * Stores one artifact and records it, replacing any previous one for the
     * same version and kind.
     *
     * @return the object key it was stored under
     */
    @Transactional
    public String write(DocumentVersion version, Artifact artifact, InputStream content,
                        long contentLength, String contentType) {
        UUID tenantId = version.getTenantId();
        // Same location the document itself lives in: the artifact is part of
        // that document, and splitting them across locations would mean a
        // tenant moving storage leaves its OCR output behind.
        StorageLocation location = storageLocationResolver.resolveForTenant(tenantId, StoragePurpose.document);
        String key = keyFor(version, artifact);

        objectStorageServiceFactory.resolve(location)
                .upload(location, key, content, contentLength, contentType);

        if (artifact == Artifact.output) {
            upsertOcrOutput(version, location, key);
        } else {
            upsertRendition(version, location, key);
        }
        log.info("Stored OCR {} for version {} ({} bytes)", artifact, version.getVersionId(), contentLength);
        return key;
    }

    /**
     * Derived from the version, never random: that is what makes a retry
     * overwrite instead of accumulate, and what lets the purge path find
     * these without a lookup.
     */
    static String keyFor(DocumentVersion version, Artifact artifact) {
        String suffix = artifact == Artifact.output ? "ocr.json" : "searchable.pdf";
        return "ocr/" + version.getTenantId() + "/" + version.getDocumentId() + "/"
                + version.getVersionId() + "/" + suffix;
    }

    private void upsertOcrOutput(DocumentVersion version, StorageLocation location, String key) {
        // JdbcTemplate rather than a JPA entity: ocr_outputs is ocr-worker's
        // table (its SQLAlchemy model owns the shape), and adding a second
        // mapping of it here would be two definitions to keep in step.
        jdbc.update("""
                INSERT INTO ocr_outputs (ocr_output_id, tenant_id, version_id, extracted_text_ref, status, created_at)
                VALUES (gen_random_uuid(), ?, ?, ?, 'completed', now())
                ON CONFLICT (version_id) DO UPDATE
                   SET extracted_text_ref = EXCLUDED.extracted_text_ref,
                       status = 'completed'
                """, version.getTenantId(), version.getVersionId(), key);
    }

    private void upsertRendition(DocumentVersion version, StorageLocation location, String key) {
        jdbc.update("""
                INSERT INTO renditions (rendition_id, tenant_id, version_id, rendition_type,
                                        storage_object_key, status, created_at)
                VALUES (gen_random_uuid(), ?, ?, 'searchable_pdf', ?, 'completed', now())
                ON CONFLICT (version_id, rendition_type) DO UPDATE
                   SET storage_object_key = EXCLUDED.storage_object_key,
                       status = 'completed'
                """, version.getTenantId(), version.getVersionId(), key);
    }

    /**
     * Destroys everything OCR produced for one version: both stored objects
     * and every row that records or derives from them. Called by the purge
     * path - see {@code RecycleBinService#purge} - so a permanently deleted
     * document takes its OCR output and searchable PDF with it.
     *
     * <h2>Derived keys AND recorded keys</h2>
     * {@link #keyFor} is deterministic, so the derived key is normally the
     * only key there is. It is not relied on as the only one: the recorded
     * {@code extracted_text_ref} / {@code storage_object_key} are read first
     * and deleted too. That covers an artifact written before the key scheme
     * existed, or by a worker build that chose its own key - exactly the
     * orphans this class was created to make impossible, and the ones that
     * would otherwise survive a purge unnoticed.
     *
     * <p>Deletion is by <strong>explicit key</strong>, never by prefix. No
     * adapter in {@code ObjectStorageService} offers a prefix delete, and the
     * purge path deliberately does not want one: a prefix sweep over
     * {@code ocr/{tenant}/{document}/} would destroy the artifacts of sibling
     * versions that are not being purged.
     *
     * <h2>Objects before rows</h2>
     * Same order as {@code RecycleBinService#purge}: destroy the objects,
     * then drop the rows. A purge interrupted half way leaves rows pointing
     * at objects that are already gone, and the next run deletes them again
     * - which is a no-op. The opposite order would leave an object with
     * nothing left in the database pointing at it, i.e. a copy of the
     * document's text that nothing can ever find or delete.
     */
    @Transactional
    public void deleteFor(DocumentVersion version) {
        StorageLocation location = storageLocationResolver.resolveForTenant(
                version.getTenantId(), StoragePurpose.document);
        var storage = objectStorageServiceFactory.resolve(location);

        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (Artifact artifact : Artifact.values()) {
            keys.add(keyFor(version, artifact));
        }
        keys.addAll(jdbc.queryForList(
                "SELECT extracted_text_ref FROM ocr_outputs WHERE version_id = ? AND extracted_text_ref IS NOT NULL",
                String.class, version.getVersionId()));
        keys.addAll(jdbc.queryForList(
                "SELECT storage_object_key FROM renditions WHERE version_id = ? AND storage_object_key IS NOT NULL",
                String.class, version.getVersionId()));

        for (String key : keys) {
            try {
                storage.delete(location, key);
            } catch (com.oak.dms.storage.exception.StorageObjectNotFoundException e) {
                // Already gone - a retried purge, or an artifact that was
                // never written. Not a reason to leave the rows behind.
                log.debug("OCR artifact {} for version {} was already gone", key, version.getVersionId());
            }
        }

        jdbc.update("DELETE FROM ocr_outputs WHERE version_id = ?", version.getVersionId());
        jdbc.update("DELETE FROM renditions WHERE version_id = ?", version.getVersionId());
        jdbc.update("DELETE FROM document_ocr_field_results WHERE version_id = ?", version.getVersionId());
        jdbc.update("DELETE FROM document_ocr_line_items WHERE version_id = ?", version.getVersionId());
        jdbc.update("DELETE FROM document_ocr_corrections WHERE version_id = ?", version.getVersionId());
        // processing_jobs is keyed by (reference_type, reference_id), not by a
        // version_id column - it carries other job types too.
        jdbc.update("DELETE FROM processing_jobs WHERE reference_type = 'document_versions' AND reference_id = ?",
                version.getVersionId());
        log.info("Destroyed {} OCR artifact(s) for version {}", keys.size(), version.getVersionId());
    }
}
