package com.oak.dms.checkout;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.DocumentVersionService;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.service.DocumentSignatureLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 2.10 Check-in / Check-out, exclusive-lock mode (CICO-FR-001..004, 009, 011).
 *
 * <h2>Why the lock is an atomic UPDATE, not "read, check, save"</h2>
 * Two people pressing Check out in the same instant must produce exactly one winner. Loading
 * the document, testing {@code checkedOutBy == null} and saving would let both pass the test.
 * {@link DocumentRepository#acquireCheckout} makes the test and the write one statement, so the
 * database decides and the loser gets {@code 0 rows updated} and a 409 naming the winner.
 *
 * <h2>Why check-in never leaves an ambiguous lock</h2>
 * A new version is produced by the SAME path as any other upload (scan first, then it becomes
 * current once clean) - there is no second, untracked way into the version history. The lock is
 * released as soon as that upload is accepted, and kept if it is refused outright (infected,
 * failed) so the holder can fix the file and try again. Every lock also carries an expiry; past
 * it the lock blocks nobody, so a crashed browser can never strand a document
 * (CICO-NFR-004).
 *
 * <h2>What this does not touch</h2>
 * Signatures and watermarks. A signature-locked or legal-hold document cannot be checked out at
 * all, and a check-in only ever ADDS a version - a signed version is never rewritten, and a
 * watermark exists only on derived copies, never in the stored file the user edits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentCheckoutService {

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentAccessService documentAccessService;
    private final DocumentVersionService versionService;
    private final DocumentSignatureLockService signatureLockService;
    private final DocumentEditGuard editGuard;
    private final SignedContentGuard signedContentGuard;
    private final AuditService auditService;

    /** How long a check-out lasts before it stops blocking others. */
    @Value("${dms.checkout.idle-timeout:PT72H}")
    private Duration idleTimeout;

    /** Who holds it, who is asking, and why it is or is not available - one answer for the UI. */
    public CheckoutStatusResponse status(UUID documentId) {
        Document document = visible(documentId, FolderAccessLevel.view);
        return statusOf(document, OffsetDateTime.now());
    }

    /** @return the status after the lock is taken (200 again, unchanged, if the caller already holds it) */
    public CheckoutStatusResponse checkOut(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID me = TenantContext.getUserId();
        Document document = visible(documentId, FolderAccessLevel.edit);
        refuseIfFrozen(document);

        OffsetDateTime now = OffsetDateTime.now();
        if (document.isCheckedOutAt(now)) {
            if (Objects.equals(document.getCheckedOutBy(), me)) {
                return statusOf(document, now); // already yours - pressing it twice is not an error
            }
            throw heldByOther(document);
        }
        UUID expiredHolder = document.getCheckedOutBy(); // non-null here only for an expired lock

        int won = documentRepository.acquireCheckout(documentId, tenantId, me, now, now.plus(idleTimeout));
        if (won == 0) {
            // Lost a race (or it was frozen in the meantime): say what is true now.
            Document current = visible(documentId, FolderAccessLevel.view);
            refuseIfFrozen(current);
            throw heldByOther(current);
        }
        if (expiredHolder != null) {
            auditService.event(AuditActionCatalog.DOCUMENT_CHECKOUT_EXPIRED)
                    .objectType(AuditObjectTypes.DOCUMENT).objectId(documentId)
                    .resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS)
                    .previousValue(Map.of("checkedOutBy", expiredHolder,
                            "expiredAt", String.valueOf(document.getCheckoutExpiresAt())))
                    .log();
        }
        Document locked = visible(documentId, FolderAccessLevel.view);
        auditService.event(AuditActionCatalog.DOCUMENT_CHECKED_OUT)
                .objectType(AuditObjectTypes.DOCUMENT).objectId(documentId)
                .resourceName(locked.getTitle()).outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("expiresAt", String.valueOf(locked.getCheckoutExpiresAt())))
                .log();
        return statusOf(locked, OffsetDateTime.now());
    }

    /**
     * Uploads the edited file as a new version and releases the lock.
     *
     * @return the same scan ticket a normal version upload returns (202 while scanning)
     */
    public IngestionTicketResponse checkIn(UUID documentId, String originalFilename, long contentLength,
                                           InputStream content, String comment) {
        UUID tenantId = TenantContext.getTenantId();
        UUID me = TenantContext.getUserId();
        Document document = visible(documentId, FolderAccessLevel.edit);
        requireHolder(document, me);
        // Signed since it was checked out (a workflow approval, a standalone signing): the edited file must not
        // replace what was signed. The holder discards the check-out instead.
        signedContentGuard.guardSaving(document);

        // Goes through DocumentVersionService.upload, whose guards let the holder through.
        IngestionTicketResponse ticket = versionService.upload(documentId, originalFilename, contentLength,
                content, comment);

        if (accepted(ticket.status())) {
            documentRepository.releaseCheckout(documentId, tenantId, me);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("scanResultId", ticket.scanResultId());
            details.put("versionId", ticket.versionId());
            details.put("heldSince", String.valueOf(document.getCheckedOutAt()));
            auditService.event(AuditActionCatalog.DOCUMENT_CHECKED_IN)
                    .objectType(AuditObjectTypes.DOCUMENT).objectId(documentId)
                    .resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS)
                    .newValue(details).log();
        }
        // Refused outright (infected / failed): the lock stays, so the holder can correct the file.
        return ticket;
    }

    /** Gives the lock up with no new version - the caller's own check-out only. */
    public CheckoutStatusResponse discard(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID me = TenantContext.getUserId();
        Document document = visible(documentId, FolderAccessLevel.edit);
        requireHolder(document, me);
        documentRepository.releaseCheckout(documentId, tenantId, me);
        auditService.event(AuditActionCatalog.DOCUMENT_CHECKOUT_DISCARDED)
                .objectType(AuditObjectTypes.DOCUMENT).objectId(documentId)
                .resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of("heldSince", String.valueOf(document.getCheckedOutAt())))
                .log();
        return statusOf(visible(documentId, FolderAccessLevel.view), OffsetDateTime.now());
    }

    /** CICO-FR-004: an administrator frees a lock left by someone who is away. Reason required, HIGH audit. */
    public CheckoutStatusResponse forceRelease(UUID documentId, String reason) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        UUID holder = document.getCheckedOutBy();
        if (holder == null || documentRepository.releaseCheckoutAnyHolder(documentId, tenantId) == 0) {
            throw CheckoutException.conflict("DOCUMENT_NOT_CHECKED_OUT", "This document isn't checked out.");
        }
        auditService.event(AuditActionCatalog.DOCUMENT_CHECKOUT_FORCE_RELEASED)
                .objectType(AuditObjectTypes.DOCUMENT).objectId(documentId)
                .resourceName(document.getTitle()).outcome(AuditOutcome.SUCCESS)
                .reason(reason)
                .previousValue(Map.of("checkedOutBy", holder,
                        "heldSince", String.valueOf(document.getCheckedOutAt())))
                .log();
        return statusOf(documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId)), OffsetDateTime.now());
    }

    // ---------------------------------------------------------------------------------------

    private static boolean accepted(ScanResultStatus status) {
        return status == ScanResultStatus.pending || status == ScanResultStatus.clean
                || status == ScanResultStatus.released_override;
    }

    /** CICO-FR-009: a signature-locked or legal-hold document cannot be checked out. */
    private void refuseIfFrozen(Document document) {
        signatureLockService.guard(document);
        if (document.isLegalHold()) {
            throw CheckoutException.conflict("DOCUMENT_LEGAL_HOLD",
                    "\"" + document.getTitle() + "\" is under legal hold and cannot be checked out.");
        }
        // Digital signatures stay intact for every file type: a signed document - by the DMS's records, a burned-in
        // signature mark, or a signature inside the file itself - is not checked out to be changed.
        signedContentGuard.guardOpening(document);
    }

    private void requireHolder(Document document, UUID me) {
        OffsetDateTime now = OffsetDateTime.now();
        if (document.isCheckedOutAt(now) && Objects.equals(document.getCheckedOutBy(), me)) {
            return;
        }
        if (document.getCheckedOutBy() != null && !document.isCheckedOutAt(now)
                && Objects.equals(document.getCheckedOutBy(), me)) {
            throw CheckoutException.conflict("CHECKOUT_EXPIRED",
                    "Your check-out of \"" + document.getTitle() + "\" expired. Check it out again.");
        }
        if (document.isCheckedOutAt(now)) {
            throw heldByOther(document);
        }
        throw CheckoutException.conflict("DOCUMENT_NOT_CHECKED_OUT",
                "\"" + document.getTitle() + "\" isn't checked out by you.");
    }

    private CheckoutException heldByOther(Document document) {
        return CheckoutException.conflict("DOCUMENT_CHECKED_OUT",
                "\"" + document.getTitle() + "\" is checked out by " + editGuard.nameOf(document.getCheckedOutBy())
                        + " since " + DocumentEditGuard.when(document.getCheckedOutAt()) + ".");
    }

    private CheckoutStatusResponse statusOf(Document d, OffsetDateTime now) {
        boolean held = d.isCheckedOutAt(now);
        // Shown as signed - and refused for check-out - whether the DSI lock is set or the version just carries a
        // valid signature / signature mark (a standalone signing sets no lock). The signature INSIDE a file needs the
        // file read, so that one is left to the check-out itself and not asked on every 2-second poll.
        boolean signed = d.getSignatureLockedAt() != null || signedContentGuard.protectedByRecord(d);
        String state = held ? "checked_out" : d.isLegalHold() ? "legal_hold" : signed ? "signature_locked" : "available";

        // Who made the current version, and when - uploaded, checked in, or saved from the online editor.
        UUID versionId = d.getCurrentVersionId() != null ? d.getCurrentVersionId() : d.getLatestVersionId();
        DocumentVersion version = versionId == null ? null : versionRepository
                .findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, d.getDocumentId(), d.getTenantId())
                .orElse(null);
        UUID editorId = version == null ? null : version.getCreatedBy();

        return new CheckoutStatusResponse(state,
                held ? d.getCheckedOutBy() : null,
                held ? editGuard.nameOf(d.getCheckedOutBy()) : null,
                held ? d.getCheckedOutAt() : null,
                held ? d.getCheckoutExpiresAt() : null,
                held && Objects.equals(d.getCheckedOutBy(), TenantContext.getUserId()),
                d.isLegalHold(), d.getLegalHoldReason(), d.getLegalHoldAt(), signed,
                editorId,
                editorId == null ? null : editGuard.nameOf(editorId),
                version == null ? null : version.getCreatedAt(),
                version == null ? 0 : version.getVersionNumber());
    }

    private Document visible(UUID documentId, FolderAccessLevel needed) {
        Document document = documentRepository
                .findByDocumentIdAndTenantIdAndDeletedFalse(documentId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        return documentAccessService.require(documentAccessService.forCaller(), document, needed);
    }
}
