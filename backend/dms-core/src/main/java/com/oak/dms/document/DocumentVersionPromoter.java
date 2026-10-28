package com.oak.dms.document;

import com.oak.dms.relationship.event.DuplicateKeptEvent;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.event.DocumentVersionCreatedEvent;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.IngestionKind;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.service.DocumentPromoter;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.ocrworker.OcrEnqueueService;
import com.oak.dms.storage.service.StorageLocationResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final OcrEnqueueService ocrEnqueueService;
    private final com.oak.dms.ocrworker.OcrProperties ocrProperties;
    private final ApplicationEventPublisher eventPublisher;

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
                .versionComment(cleanResult.getVersionComment())
                // 'overridden' only for a quarantine override release (V40) -
                // the gate trigger pairs it with the released_override result
                // and the reviewed quarantine record behind it.
                .scanStatus(override ? DocumentVersion.SCAN_STATUS_OVERRIDDEN : DocumentVersion.SCAN_STATUS_CLEAN)
                .createdBy(uploadedBy);

        DocumentVersionCreatedEvent pendingVersionEvent = null;
        if (cleanResult.getIngestionKind() == IngestionKind.new_document) {
            documentRepository.save(Document.builder()
                    .documentId(plan.documentId())
                    .tenantId(tenantId)
                    .documentTypeId(cleanResult.getDocumentTypeId())
                    .folderId(cleanResult.getFolderId())
                    .title(cleanResult.getTitle())
                    .template(DocumentTemplateService.CREATED_REASON.equals(cleanResult.getCreatedReason()))
                    .currentVersionId(plan.versionId())
                    .latestVersionId(plan.versionId())
                    .ownerUserId(uploadedBy)
                    .createdBy(uploadedBy)
                    .build());
            version.majorVersion(1).minorVersion(0).versionLabel("1.0");
            if (cleanResult.getDuplicateOfDocumentId() != null) {
                // "Keep both" after duplicate detection (2.8): the link is made after this commits, in its own
                // transaction, so a problem with it can never undo or fail the upload.
                eventPublisher.publishEvent(new DuplicateKeptEvent(tenantId, plan.documentId(),
                        cleanResult.getDuplicateOfDocumentId(), uploadedBy, cleanResult.getScanResultId()));
            }
        } else {
            Document document = findDocument(plan.documentId(), tenantId);
            DocumentVersion latest = documentVersionRepository
                    .findTopByDocumentIdOrderByVersionNumberDesc(document.getDocumentId())
                    .orElse(null);
            // Minor increment on the latest version; publishing/major-version
            // rules belong to the versioning module once it exists.

            int major = latest != null ? latest.getMajorVersion() : 1;
            int minor = latest != null ? latest.getMinorVersion() + 1 : 0;
            version.majorVersion(major).minorVersion(minor).versionLabel(major + "." + minor);
            // The newest clean version is the one opened and downloaded - there's no approval step that
            // could hold it back yet (a restore arrives here too, as a new 'rollback' version).

            version.majorVersion(latest != null ? latest.getMajorVersion() : 1)
                    .minorVersion(latest != null ? latest.getMinorVersion() + 1 : 0);
            UUID previousLatest = document.getLatestVersionId();

            document.setLatestVersionId(plan.versionId());
            document.setCurrentVersionId(plan.versionId());
            document.setUpdatedBy(uploadedBy);
            document.setUpdatedAt(OffsetDateTime.now());
            documentRepository.save(document);
            // Synchronous listeners run inside this promotion transaction -
            // the workflow module flags or restarts an in-flight approval
            // (decision 10) atomically with the new version.
            pendingVersionEvent = new DocumentVersionCreatedEvent(tenantId, document.getDocumentId(), previousLatest,
                    plan.versionId(), uploadedBy);
        }

        // OCR, if the uploader asked for it. This is the ONLY place it starts.
        //
        // Here rather than at upload because here is the first moment all three
        // things are true at once: the file has a clean verdict, a version row
        // exists to hang the job off, and we are inside the transaction that
        // commits both. Starting it any earlier would hand the worker an
        // unscanned file; any later would need a second transaction that could
        // fail on its own and leave a clean version nobody ever OCRs.
        String requested = cleanResult.getOcrRequestedFields();
        // `dms.ocr.enabled` is checked HERE as well as in OcrEnqueueService,
        // and the difference matters. The enqueue service declining means
        // "not now" - it leaves the version pending on purpose, as the
        // durable marker the backlog sweeper drains. The feature being off
        // means "not ever on this deployment", and there is no sweeper
        // running to drain it. Marking the version pending in that case would
        // leave every uploaded document stuck in a state nothing can move it
        // out of, which is the opposite of "with it off nothing happens".
        //
        // Reached mainly by bulk import, whose batch-level field selection is
        // stored when the batch is created and could outlive the flag being
        // turned off mid-import.
        boolean wantsOcr = ocrProperties.isEnabled()
                && requested != null && !requested.isBlank() && !"[]".equals(requested.trim());
        if (wantsOcr) {
            version.requestedFields(requested)
                    // 'pending' before the enqueue decides anything. If the
                    // enqueue declines (OCR off, tenant at its queue limit, or
                    // an import over its cap) this row IS the durable marker
                    // the backlog sweeper looks for - pending, with fields
                    // requested, and no live job.
                    .ocrStatus("pending");
        }

        // Safe in either order with the document row and the scan result link -
        // the FKs and the V39 gate trigger are all DEFERRABLE INITIALLY DEFERRED.
        documentVersionRepository.save(version.build());

        if (wantsOcr) {
            boolean queued = ocrEnqueueService.enqueue(tenantId, plan.documentId(), plan.versionId(),
                    // The scan result id doubles as the correlation id: it ties the OCR
                    // job back to the exact ingestion that produced the version.
                    cleanResult.getScanResultId(),
                    cleanResult.isOcrLowPriority()
                            ? OcrEnqueueService.Source.bulk_import
                            : OcrEnqueueService.Source.interactive);
            if (!queued) {
                log.info("OCR requested for version {} but not queued now - left for the backlog sweeper",
                        plan.versionId());
            }
        }

        if (pendingVersionEvent != null) {
            eventPublisher.publishEvent(pendingVersionEvent);
        }
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
