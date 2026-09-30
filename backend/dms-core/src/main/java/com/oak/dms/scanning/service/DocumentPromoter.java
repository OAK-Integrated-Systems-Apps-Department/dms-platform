package com.oak.dms.scanning.service;

import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.storage.entity.StorageLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * The single place a {@code DocumentVersion} is ever created - implemented by
 * {@code com.oak.dms.document.DocumentVersionPromoter}, called only by the
 * ingestion gate for a scan result it holds, with a CLEAN verdict. Defined
 * here (not in the document module) so the gate can promote asynchronously
 * - from the retry scheduler or a cloud callback - without depending on
 * document internals. Enforced beyond convention by V39's
 * {@code trg_document_versions_require_clean_scan}.
 * <p>
 * Never reads {@code TenantContext}: tenant and uploader come from the
 * {@link ScanResult}, since async promotion has no request behind it.
 */
public interface DocumentPromoter {

    /** Decides ids, version number, document location and object key. No DB write. */
    PromotionPlan prepare(ScanResult cleanResult);

    /**
     * Inserts the {@code Document} (for {@code new_document}) and the
     * {@code DocumentVersion}, with {@code scan_status = clean}. Must run in
     * the caller's transaction - the same one that marks the scan result
     * clean and links it, which the V39 trigger checks at commit.
     */
    void persist(PromotionPlan plan, ScanResult cleanResult);

    Optional<UUID> findDocumentIdForVersion(UUID tenantId, UUID versionId);

    record PromotionPlan(
            UUID documentId,
            UUID versionId,
            int versionNumber,
            StorageLocation documentLocation,
            String objectKey
    ) {
    }
}
