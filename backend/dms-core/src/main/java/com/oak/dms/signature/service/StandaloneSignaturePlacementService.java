package com.oak.dms.signature.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.department.entity.Department;
import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentContentReader;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.dto.PlaceSignatureRequest;
import com.oak.dms.signature.dto.PlaceSignatureResponse;
import com.oak.dms.signature.dto.SignaturePlacementInfoResponse;
import com.oak.dms.signature.enums.VisualSignatureKind;
import com.oak.dms.signature.exception.SignatureException;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Lets a user add an e-signature straight to a document - the same visual
 * mark + Name/Designation/Date block as {@link SignaturePlacementService},
 * burned into a real new version the same way, but keyed off the document
 * itself rather than an open, actionable workflow task. This is the path
 * for "sign this document" outside of approving anything.
 * <p>
 * Mirrors {@link SignaturePlacementService} closely (see that class's own
 * doc comment for why stamping has to happen outside any transaction, via
 * the real ingestion/scan pipeline, with a short dedicated transaction only
 * for the pointer update after) - deliberately not shared code, since the
 * two resolve their target through entirely different chains (a document
 * directly here, vs. task -> stage instance -> workflow instance there) and
 * revert to a baseline that resets after every full removal here, instead of
 * staying pinned for one task's entire lifetime.
 * <p>
 * "No room on this page" is never auto-detected (there's no reliable way to
 * read "free space" back out of an arbitrary PDF's content stream) - the
 * signer either drags the mark onto an existing page via {@link #place}, or
 * explicitly appends a fresh blank page via {@link #addPage} first and then
 * places it there.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandaloneSignaturePlacementService {

    private final DocumentRepository documentRepository;
    /** Legal hold / someone else's check-out (V127): adding a page, placing or removing a mark all make a new version. */
    private final com.oak.dms.checkout.DocumentEditGuard editGuard;
    private final DocumentVersionRepository versionRepository;
    private final DocumentContentReader contentReader;
    private final DocumentStampService stampService;
    private final TypedSignatureRenderer typedSignatureRenderer;
    private final UserVisualSignatureService visualSignatureService;
    private final IngestionGate ingestionGate;
    private final AuditService auditService;
    private final PlatformTransactionManager transactionManager;
    private final SignatureStampComposer stampComposer;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final DocumentSignatureLockService lockService;
    private final WorkflowInstanceRepository instanceRepository;

    public SignaturePlacementInfoResponse info(UUID documentId) {
        Document document = resolve(documentId);
        UUID versionId = baseVersionId(document);
        DownloadedDocument current = open(documentId, versionId);
        boolean supported = stampService.supports(current.contentType());
        int pageCount = supported ? stampService.pageCount(readAll(current), current.contentType()) : 1;
        return new SignaturePlacementInfoResponse(documentId, versionId, current.contentType(), supported, pageCount,
                document.getStandaloneSignatureBaseVersionId() != null);
    }

    /** A JPEG (PDF) or the original bytes (an already-an-image document) for {@code page} (0-based). */
    public byte[] pagePreview(UUID documentId, int page, int width) {
        Document document = resolve(documentId);
        DownloadedDocument current = open(documentId, baseVersionId(document));
        if (!stampService.supports(current.contentType())) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_UNSUPPORTED_TYPE",
                    "Placing a signature on the page isn't supported for this file type.");
        }
        return stampService.renderPagePreview(readAll(current), current.contentType(), page, width);
    }

    /**
     * Appends a blank page (same size as the current last page) when no existing page has room for the
     * signature block - a deliberate, explicit action rather than something inferred from "how full" a
     * page looks (see this class's own doc comment for why that can't be done reliably). The new page
     * becomes the last one; the signer then drags the mark onto it via {@link #place} like any other.
     */
    public SignaturePlacementInfoResponse addPage(UUID documentId) {
        Document document = resolve(documentId);
        lockService.guard(document);
        editGuard.guard(document);
        guardNoActiveWorkflow(documentId);
        boolean firstAction = document.getStandaloneSignatureBaseVersionId() == null;
        UUID baseVersionId = baseVersionId(document);
        DownloadedDocument current = open(documentId, baseVersionId);
        String mimeType = current.contentType();

        byte[] withPage = stampService.appendBlankPage(readAll(current), mimeType);

        IngestionTicketResponse ticket = ingestionGate.submit(IngestionRequest.newVersion(
                documentId, "signed_copy", current.filename(), withPage.length,
                new ByteArrayInputStream(withPage), "Signature page added"));
        requireClean(ticket, documentId);
        advanceDocument(document, ticket.versionId(), firstAction, baseVersionId);

        auditService.event(AuditActionCatalog.DOCUMENT_SIGNATURE_PLACED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("versionId", ticket.versionId(), "action", "page_added"))
                .log();

        return new SignaturePlacementInfoResponse(documentId, ticket.versionId(), mimeType, true,
                stampService.pageCount(withPage, mimeType), true);
    }

    public PlaceSignatureResponse place(UUID documentId, PlaceSignatureRequest request) {
        Document document = resolve(documentId);
        lockService.guard(document);
        editGuard.guard(document);
        guardNoActiveWorkflow(documentId);
        boolean firstAction = document.getStandaloneSignatureBaseVersionId() == null;
        UUID baseVersionId = baseVersionId(document);
        DownloadedDocument current = open(documentId, baseVersionId);
        String mimeType = current.contentType();
        if (!stampService.supports(mimeType)) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_UNSUPPORTED_TYPE",
                    "Placing a signature on the page isn't supported for this file type.");
        }
        byte[] sourceBytes = readAll(current);

        UUID tenantId = TenantContext.getTenantId();
        byte[] markPng = request.visualSignatureKind() == VisualSignatureKind.typed
                ? typedSignatureRenderer.render(visualSignatureService.validateTypedText(request.visualSignatureText()))
                : visualSignatureService.validateImage(request.visualSignatureImageBase64());
        // Cosmetic choice only (see PlaceSignatureRequest#includeSignerDetails's own doc comment).
        byte[] composedMark = request.includeSignerDetails()
                ? stampComposer.compose(markPng, signerInfo(tenantId, TenantContext.getUserId()))
                : markPng;

        byte[] stamped = stampService.stamp(sourceBytes, mimeType, request.page(), request.xRatio(), request.yRatio(),
                request.widthRatio(), request.heightRatio(), composedMark);

        IngestionTicketResponse ticket = ingestionGate.submit(IngestionRequest.newVersion(
                documentId, "signed_copy", current.filename(), stamped.length,
                new ByteArrayInputStream(stamped), "Signature placed on page " + (request.page() + 1)));
        requireClean(ticket, documentId);
        advanceDocument(document, ticket.versionId(), firstAction, baseVersionId);

        DocumentVersion saved = versionRepository
                .findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(ticket.versionId(), documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", ticket.versionId()));

        auditService.event(AuditActionCatalog.DOCUMENT_SIGNATURE_PLACED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("versionId", saved.getVersionId(), "versionNumber", saved.getVersionNumber(),
                        "page", request.page()))
                .log();

        return new PlaceSignatureResponse(saved.getVersionId(), saved.getVersionNumber());
    }

    /**
     * Undoes every standalone signing action taken on this document since it last had none - reverts
     * straight to the pinned baseline (never deleting any stamped version in between, just no longer
     * current) and clears the pin, so the next "sign this document" starts a fresh session from whatever
     * is current then.
     *
     * @throws SignatureException 409 if nothing is currently placed
     */
    public void remove(UUID documentId) {
        Document document = resolve(documentId);
        lockService.guard(document);
        editGuard.guard(document);
        UUID baseline = document.getStandaloneSignatureBaseVersionId();
        if (baseline == null) {
            throw SignatureException.conflict("SIGNATURE_PLACEMENT_NONE",
                    "This document hasn't had a signature placed on it yet.");
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            document.setCurrentVersionId(baseline);
            document.setStandaloneSignatureBaseVersionId(null);
            documentRepository.save(document);
        });
        auditService.event(AuditActionCatalog.DOCUMENT_SIGNATURE_PLACEMENT_REMOVED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("revertedToVersionId", baseline))
                .log();
    }

    /** Name/position/department/now, for {@link SignatureStampComposer} - position/department are each omitted when unset, never shown blank. */
    private SignatureStampComposer.SignerInfo signerInfo(UUID tenantId, UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
        String department = user.getDepartmentId() == null ? null
                : departmentRepository.findByDepartmentIdAndTenantIdAndDeletedFalse(user.getDepartmentId(), tenantId)
                        .map(Department::getName).orElse(null);
        return new SignatureStampComposer.SignerInfo(user.getDisplayName(), user.getPosition(), department,
                OffsetDateTime.now());
    }

    /** This document's pinned baseline if a standalone session is already in progress, otherwise its current version. */
    private static UUID baseVersionId(Document document) {
        UUID pinned = document.getStandaloneSignatureBaseVersionId();
        if (pinned != null) {
            return pinned;
        }
        return document.getCurrentVersionId() != null ? document.getCurrentVersionId() : document.getLatestVersionId();
    }

    /**
     * A @Modifying query needs its own active transaction (see SignaturePlacementService's own doc
     * comment on why this whole class otherwise runs with none) - a short, dedicated one is opened just
     * for this write. Both fields change together: re-pointing the document at the new stamp, and -
     * only the first action of a session - pinning the baseline so a later addPage/place/remove in the
     * same session keeps reading from/reverting to it, not from whatever this call itself just produced.
     */
    private void advanceDocument(Document document, UUID newVersionId, boolean firstAction, UUID baseVersionId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            document.setCurrentVersionId(newVersionId);
            if (firstAction) {
                document.setStandaloneSignatureBaseVersionId(baseVersionId);
            }
            documentRepository.save(document);
        });
    }

    private void requireClean(IngestionTicketResponse ticket, UUID documentId) {
        if (ticket.status() != ScanResultStatus.clean || ticket.versionId() == null) {
            log.warn("Signed copy for document {} did not complete synchronously (status={})", documentId, ticket.status());
            throw SignatureException.unavailable("SIGNATURE_PLACEMENT_NOT_READY",
                    "Could not finish preparing the signed copy just now - please try again.");
        }
    }

    private Document resolve(UUID documentId) {
        return documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
    }

    /**
     * Refuses standalone signing while a workflow approval is actively in progress on this document -
     * otherwise a signer unrelated to that approval (anyone holding DSI_SIGN_DOCUMENT_STANDALONE, not
     * just whoever the workflow assigned) could add their own independent signature mid-approval,
     * producing two unrelated signature trails on the same document at once. Standalone signing is still
     * available once the workflow finishes (approved/rejected/cancelled) or if none was ever started.
     */
    private void guardNoActiveWorkflow(UUID documentId) {
        if (instanceRepository.findFirstByTenantIdAndDocumentIdAndStatus(TenantContext.getTenantId(), documentId,
                InstanceStatus.in_progress).isPresent()) {
            throw SignatureException.conflict("DOCUMENT_WORKFLOW_IN_PROGRESS",
                    "This document has an approval workflow in progress - standalone signing isn't available until it finishes.");
        }
    }

    private DownloadedDocument open(UUID documentId, UUID versionId) {
        return contentReader.open(documentId, versionId);
    }

    private static byte[] readAll(DownloadedDocument document) {
        try (var in = document.content()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the document for signature placement", e);
        }
    }
}
