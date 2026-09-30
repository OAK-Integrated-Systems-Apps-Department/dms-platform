package com.oak.dms.document;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.IngestionKind;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.service.DocumentPromoter;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.service.StorageLocationResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>The only class that creates a {@link DocumentVersion}.</b> Implements the
 * scan gate's {@link DocumentPromoter} and is called only by the gate, for a
 * scan result with a CLEAN verdict (or, since V40, an administrator's
 * explicit quarantine override - see {@code QuarantineReleaseService}) - every creation path (initial upload
 * today; replacement upload, workflow update, signed copy and migration
 * later) arrives here through
 * {@link com.oak.dms.scanning.service.IngestionGate#submit}. Don't add a
 * second place that builds a {@code DocumentVersion}: V39's
 * {@code trg_document_versions_require_clean_scan} rejects any version whose
 * commit isn't linked to a clean scan result with the same file hash in a
 * document-purpose location - see docs/scanning-architecture.md.
 * <p>
 * Never reads {@code TenantContext}: tenant and uploader come from the scan
 * result, since promotion may run in the retry scheduler or a cloud callback.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentVersionPromoter implements DocumentPromoter {

    private static final int INITIAL_VERSION_NUMBER = 1;

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final StorageLocationResolver storageLocationResolver;

    @Override
    @Transactional(readOnly = true)
    public PromotionPlan prepare(ScanResult cleanResult) {
        UUID tenantId = cleanResult.getTenantId();
        StorageLocation location = storageLocationResolver.resolveForTenant(tenantId, StoragePurpose.document);

        UUID documentId;
        int versionNumber;
        if (cleanResult.getIngestionKind() == IngestionKind.new_document) {
            documentId = UUID.randomUUID();
            versionNumber = INITIAL_VERSION_NUMBER;
        } else {
            Document document = findDocument(cleanResult.getTargetDocumentId(), tenantId);
            documentId = document.getDocumentId();
            versionNumber = documentVersionRepository.findTopByDocumentIdOrderByVersionNumberDesc(documentId)
                    .map(latest -> latest.getVersionNumber() + 1)
                    .orElse(INITIAL_VERSION_NUMBER);
        }
        UUID versionId = UUID.randomUUID();
        return new PromotionPlan(documentId, versionId, versionNumber, location,
                buildObjectKey(tenantId, documentId, versionNumber, versionId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void persist(PromotionPlan plan, ScanResult cleanResult) {
        boolean clean = cleanResult.getStatus() == ScanResultStatus.clean;
        boolean override = cleanResult.getStatus() == ScanResultStatus.released_override;
        if ((!clean && !override) || !plan.versionId().equals(cleanResult.getDocumentVersionId())) {
            throw new IllegalStateException("Scan result " + cleanResult.getScanResultId()
                    + " is neither a clean result nor an administrator override linked to version " + plan.versionId());
        }
        UUID tenantId = cleanResult.getTenantId();
        UUID uploadedBy = cleanResult.getUploadedBy();

        DocumentVersion.DocumentVersionBuilder version = DocumentVersion.builder()
                .versionId(plan.versionId())
                .documentId(plan.documentId())
                .tenantId(tenantId)
                .versionNumber(plan.versionNumber())
                .storageLocationId(plan.documentLocation().getStorageLocationId())
                .storageObjectKey(plan.objectKey())
                .originalFilename(cleanResult.getOriginalFilename())
                .mimeType(cleanResult.getMimeType())
                .fileHash(cleanResult.getFileHash())
                .sizeBytes(cleanResult.getSizeBytes())
                .createdReason(cleanResult.getCreatedReason())
                // 'overridden' only for a quarantine override release (V40) -
                // the gate trigger pairs it with the released_override result
                // and the reviewed quarantine record behind it.
                .scanStatus(override ? DocumentVersion.SCAN_STATUS_OVERRIDDEN : DocumentVersion.SCAN_STATUS_CLEAN)
                .createdBy(uploadedBy);

        if (cleanResult.getIngestionKind() == IngestionKind.new_document) {
            documentRepository.save(Document.builder()
                    .documentId(plan.documentId())
                    .tenantId(tenantId)
                    .documentTypeId(cleanResult.getDocumentTypeId())
                    .title(cleanResult.getTitle())
                    .currentVersionId(plan.versionId())
                    .latestVersionId(plan.versionId())
                    .ownerUserId(uploadedBy)
                    .createdBy(uploadedBy)
                    .build());
            version.majorVersion(1).minorVersion(0);
        } else {
            Document document = findDocument(plan.documentId(), tenantId);
            DocumentVersion latest = documentVersionRepository
                    .findTopByDocumentIdOrderByVersionNumberDesc(document.getDocumentId())
                    .orElse(null);
            // Minor increment on the latest version; publishing/major-version
            // rules belong to the versioning module once it exists.
            version.majorVersion(latest != null ? latest.getMajorVersion() : 1)
                    .minorVersion(latest != null ? latest.getMinorVersion() + 1 : 0);
            document.setLatestVersionId(plan.versionId());
            document.setUpdatedBy(uploadedBy);
            document.setUpdatedAt(OffsetDateTime.now());
            documentRepository.save(document);
        }

        // Safe in either order with the document row and the scan result link -
        // the FKs and the V39 gate trigger are all DEFERRABLE INITIALLY DEFERRED.
        documentVersionRepository.save(version.build());
        log.info("Promoted scan result {} to document {} version {} (tenant {})", cleanResult.getScanResultId(),
                plan.documentId(), plan.versionId(), tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findDocumentIdForVersion(UUID tenantId, UUID versionId) {
        return documentVersionRepository.findById(versionId)
                .filter(version -> version.getTenantId().equals(tenantId))
                .map(DocumentVersion::getDocumentId);
    }

    private Document findDocument(UUID documentId, UUID tenantId) {
        return documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
    }

    /**
     * Never the raw uploaded filename - always server-generated from UUIDs
     * only, so an object key can never collide, overwrite another object,
     * or be used to traverse/guess another tenant's objects. See
     * docs/storage-architecture.md's object-key section.
     */
    private static String buildObjectKey(UUID tenantId, UUID documentId, int versionNumber, UUID versionId) {
        return "documents/" + tenantId + "/" + documentId + "/v" + versionNumber + "/" + versionId;
    }
}
