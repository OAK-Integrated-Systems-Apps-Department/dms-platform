package com.oak.dms.document;

import com.oak.dms.relationship.event.DuplicateKeptEvent;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.event.DocumentVersionCreatedEvent;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.IngestionKind;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.exception.PromotionRefusedException;
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
    private final com.oak.dms.user.UserRepository userRepository;
    // DOC-06: the document's state is re-checked at promotion (see requirePromotable) - signed-by-record uses the
    // same guard as upload, restore, check-in and the editor.
    private final com.oak.dms.checkout.SignedContentGuard signedContentGuard;
    private final jakarta.persistence.EntityManager entityManager;

    /** created_reason of a version made by placing a signature mark - see {@link #requirePromotable}. */
    private static final String SIGNED_COPY = "signed_copy";

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
            Document document = findPromotableDocument(cleanResult.getTargetDocumentId(), tenantId);
            // An early, unlocked look so a refusal costs no file copy; persist() decides, under a row lock.
            requirePromotable(document, cleanResult);
            documentId = document.getDocumentId();
            // Only a HINT (DOC-08): decided here without a lock, so it can be stale by the time persist() runs.
            // persist() assigns the real number under the document's row lock.
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
                    .ownerDepartmentId(departmentOf(uploadedBy, tenantId))
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
            // DOC-06: the scan took however long it took, and the document may have been deleted, locked, held or
            // signed meanwhile. Under the document's row lock - so a concurrent lock/hold/delete either committed
            // before this read or waits for this transaction - and before anything is written, check again.
            Document document = lockPromotableDocument(plan.documentId(), tenantId, cleanResult);
            // The newest committed version, read AFTER the lock is held: any promotion of this document that ran
            // before this one has committed its row by now (every version is created here, under this same lock, and
            // this read is a new statement at READ COMMITTED), and any that runs later waits for this transaction.
            DocumentVersion latest = documentVersionRepository
                    .findTopByDocumentIdOrderByVersionNumberDesc(document.getDocumentId())
                    .orElse(null);
            // DOC-08: the number is assigned HERE, from that row - not taken from the plan. prepare() chose
            // plan.versionNumber() long before (before the file was copied, outside any lock), so two uploads of one
            // document prepared together both proposed the same number and the second insert hit
            // uq_document_versions_document_number. The plan's number is only a hint, used for the "v<N>" folder in the
            // object key; the row stores its own key and nothing reads the number back out of a key.
            int finalNumber = latest != null ? latest.getVersionNumber() + 1 : INITIAL_VERSION_NUMBER;
            if (finalNumber != plan.versionNumber()) {
                log.debug("Version of document {} numbered {} (prepared as {}): another version was promoted meanwhile",
                        plan.documentId(), finalNumber, plan.versionNumber());
            }
            version.versionNumber(finalNumber);
            // Minor increment on the latest version; publishing/major-version
            // rules belong to the versioning module once it exists.
            int major = latest != null ? latest.getMajorVersion() : 1;
            int minor = latest != null ? latest.getMinorVersion() + 1 : 0;
            version.majorVersion(major).minorVersion(minor).versionLabel(major + "." + minor);
            // The newest clean version is the one opened and downloaded - there's no approval step that
            // could hold it back yet (a restore arrives here too, as a new 'rollback' version).
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
        // Its preview and thumbnail are made once this commits (DPRM-FR-001/003) - in the background, so the
        // upload never waits for a conversion.
        eventPublisher.publishEvent(new com.oak.dms.document.event.DocumentVersionStoredEvent(tenantId,
                plan.documentId(), plan.versionId()));
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

    /** The tenant's live document, or a refusal: deleted, or not this tenant's (never said apart, so nothing leaks). */
    private Document findPromotableDocument(UUID documentId, UUID tenantId) {
        return documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(DocumentVersionPromoter::documentUnavailable);
    }

    /**
     * {@link #findPromotableDocument} under {@code SELECT ... FOR UPDATE}
     * ({@link DocumentRepository#lockByDocumentIdAndTenantId} - the first lock of the documented lock order, documents
     * before workflow instances, and the one the synchronous version-change listener takes in this same transaction
     * anyway), then {@link #requirePromotable}. The lock query hands back an entity that is already in the
     * persistence context as it was loaded, not as the row now is, so it is refreshed after the lock is held.
     */
    private Document lockPromotableDocument(UUID documentId, UUID tenantId, ScanResult scan) {
        Document document = documentRepository.lockByDocumentIdAndTenantId(documentId, tenantId)
                .orElseThrow(DocumentVersionPromoter::documentUnavailable);
        entityManager.refresh(document);
        requirePromotable(document, scan);
        return document;
    }

    /**
     * Whether a new version may be added to {@code document} now. Refuses - permanently, see
     * {@link PromotionRefusedException} - a document under a workflow signature lock or a legal hold, and (the DOC-05
     * rule) a user's upload or restore over a version that is signed by a signature record or a {@code signed_copy}
     * mark ({@link com.oak.dms.checkout.SignedContentGuard#protectedByRecord}; the signature inside a FILE cannot
     * appear on a version that already exists, so the records are what can change during a scan).
     * <p>
     * The decision rests only on the scan result's {@code created_reason}, which server code sets when the upload is
     * submitted ({@code DocumentVersionService}, the editor, template replacement, signature placement) and the client
     * can never supply. Exactly one reason is exempt, {@code signed_copy}: signing a document produces that version
     * itself, often on top of a previous {@code signed_copy} (adding a page, placing again), so the signature rules
     * would refuse signing. A check-in, an online save, a bulk-import version, a restore and a template replacement
     * are ordinary here.
     * <p>
     * The check-out is checked too (DOC-07, {@link #requireCheckoutAllows}): the new version may not arrive across a
     * check-out that is not the uploader's own.
     * <p>
     * FOLLOW-UP: the workflow signature placement ({@code SignaturePlacementService}) has no request-time lock or
     * legal-hold guard, unlike the standalone one, so exempting {@code signed_copy} here keeps that existing behaviour;
     * a hold placed during a workflow's stamping step is not caught. Deleted documents are refused for every reason.
     */
    private void requirePromotable(Document document, ScanResult scan) {
        if (SIGNED_COPY.equals(scan.getCreatedReason())) {
            return;
        }
        String title = "\"" + document.getTitle() + "\"";
        if (document.getSignatureLockedAt() != null) {
            throw new PromotionRefusedException("DOCUMENT_SIGNATURE_LOCKED", title + " was locked because it carries a "
                    + "valid signature while the file was being scanned, so the new version was not added.");
        }
        if (document.isLegalHold()) {
            throw new PromotionRefusedException("DOCUMENT_LEGAL_HOLD", title + " was placed under legal hold while the "
                    + "file was being scanned, so the new version was not added.");
        }
        if (signedContentGuard.protectedByRecord(document)) {
            throw new PromotionRefusedException(com.oak.dms.checkout.SignedContentGuard.CODE, title + " was signed "
                    + "while the file was being scanned, so the new version was not added.");
        }
        requireCheckoutAllows(document, scan, title);
    }

    /**
     * DOC-07: the check-out as it is NOW, under the row lock, against the upload as it was SUBMITTED. A lease is one
     * acquisition of the check-out - who holds it and when it was taken ({@code checked_out_by}, {@code checked_out_at};
     * the expiry is fixed at acquisition and an expired lease stays on the row until someone takes it over or it is
     * released). The upload is identified by the two things the gate recorded when it accepted it and that nothing
     * client-supplied can set: {@code uploaded_by} and {@code started_at}. So, with {@code now} the promotion time:
     * <ul>
     *   <li>nobody holds an unexpired check-out - allowed, as any upload (a check-out that was force-released leaves no
     *       trace on the row, so it cannot be told from "never checked out": see the limitation in the design doc);</li>
     *   <li>someone ELSE holds one - refused (a takeover after expiry, or a check-out taken while the file was scanning);</li>
     *   <li>the uploader holds one taken AFTER the upload was submitted - refused: a new lease, not the one this file
     *       was submitted under;</li>
     *   <li>the uploader holds one taken at or before the submission - allowed: their own lease, the check-in's;</li>
     *   <li>the uploader's lease that existed at submission has since EXPIRED (still on the row, no one took over) -
     *       refused: the check-out this file was checked in under ended while it was being scanned.</li>
     * </ul>
     * Never decided by user name alone, never by anything a client sends. A missing {@code started_at} (the gate always
     * sets it) is treated as "not provably before", i.e. fail closed. Called for every reason but {@code signed_copy}.
     */
    private void requireCheckoutAllows(Document document, ScanResult scan, String title) {
        java.util.UUID holder = document.getCheckedOutBy();
        if (holder == null) {
            return;
        }
        boolean uploaderIsHolder = holder.equals(scan.getUploadedBy());
        OffsetDateTime leaseTaken = document.getCheckedOutAt();
        boolean leasePredatesUpload = leaseTaken != null && scan.getStartedAt() != null
                && !leaseTaken.isAfter(scan.getStartedAt());
        if (document.isCheckedOutAt(OffsetDateTime.now())) {
            if (!uploaderIsHolder) {
                throw new PromotionRefusedException("DOCUMENT_CHECKED_OUT", title + " is checked out by someone else, so "
                        + "the new version was not added. It was either submitted before that check-out or after the "
                        + "check-out it was checked in under ended.");
            }
            if (!leasePredatesUpload) {
                throw new PromotionRefusedException("DOCUMENT_CHECKED_OUT", title + " was checked out again after this "
                        + "file was submitted, so the new version was not added.");
            }
        } else if (uploaderIsHolder && leasePredatesUpload) {
            throw new PromotionRefusedException("CHECKOUT_EXPIRED", "Your check-out of " + title + " expired while the "
                    + "file was being scanned, so the new version was not added. Check it out again and re-upload.");
        }
    }

    private static PromotionRefusedException documentUnavailable() {
        return new PromotionRefusedException("DOCUMENT_UNAVAILABLE",
                "The document was deleted while the file was being scanned, so the new version was not added.");
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

    /** The uploader's primary department (the owner department of a new document); null if none or unknown. */
    private UUID departmentOf(UUID userId, UUID tenantId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .filter(user -> tenantId.equals(user.getTenantId()))
                .map(com.oak.dms.user.User::getDepartmentId)
                .orElse(null);
    }
}
