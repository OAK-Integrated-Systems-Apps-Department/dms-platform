package com.oak.dms.document;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Reads a document's bytes for a feature outside the {@code document}
 * package, applying the same checks a download does.
 *
 * <h2>Why this exists rather than widening VersionContent</h2>
 * {@link VersionContent} is package-private and does half the job: it
 * enforces the STORAGE side - the location belongs to this tenant (or the
 * platform), it holds documents rather than staging or quarantine, the
 * version was scanned clean or released by an administrator, and it has not
 * been purged. What it deliberately does not do is check whether the CALLER
 * may see the document; every caller inside the package does that first.
 * <p>
 * Making it public would hand a new module the storage checks while
 * silently leaving out the access check - the one whose absence looks
 * exactly like working code. This facade pairs the two so there is no way
 * to take one without the other, and {@code VersionContent} stays
 * package-private and unchanged.
 * <p>
 * The "current else latest" rule is duplicated from
 * {@code DocumentServiceImpl#serve} and {@code DocumentRenditionService} on
 * purpose: those pick a version to serve to a person now, this one pins a
 * version to a job that may run minutes later. A job must record WHICH
 * version it watermarked, so {@link #describe} returns it and the worker
 * re-opens that exact version rather than whatever is current by then.
 *
 * <h2>Transactions</h2>
 * Read-write, not read-only, for the same reason {@code downloadDocument}
 * is: an audit row written by a caller in this transaction is silently
 * dropped by a read-only one.
 */
@Component
@RequiredArgsConstructor
public class DocumentContentReader {

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentAccessService documentAccessService;
    private final VersionContent versionContent;

    /**
     * What a job needs to know about a version before deciding whether to
     * accept the work - without reading a single byte.
     *
     * @param sizeBytes       from the version row, so a size cap can be applied
     *                        before anything is opened
     * @param mimeType        the canonical type, not the client's Content-Type
     */
    public record VersionDetails(
            UUID documentId,
            UUID versionId,
            int versionNumber,
            String title,
            String originalFilename,
            String mimeType,
            long sizeBytes) {
    }

    /**
     * Describes the version this request is about, after checking the caller
     * may open the document.
     *
     * @param requestedVersion a specific version, or null for the document's
     *                         current (else latest) one
     * @throws ResourceNotFoundException for a document in another tenant, one
     *         that is deleted, or one the caller can't see - all 404, so a
     *         hidden document is indistinguishable from a missing one
     */
    @Transactional(readOnly = true)
    public VersionDetails describe(UUID documentId, UUID requestedVersion) {
        Document document = visible(documentId);
        DocumentVersion version = version(document, requestedVersion);
        // The scan gate, applied HERE as well as in VersionContent. Both
        // matter: VersionContent is what actually refuses to hand over the
        // bytes, but a caller that only describes a version - to decide
        // whether to accept a job, say - would otherwise accept work on a
        // version it will never be allowed to read, and discover that in a
        // background thread. Checked up front, it is a refusal the person
        // asking can be told about.
        if (!version.isServable()) {
            throw new DocumentNotScannedException(documentId);
        }
        return new VersionDetails(document.getDocumentId(), version.getVersionId(), version.getVersionNumber(),
                document.getTitle(), version.getOriginalFilename(), version.getMimeType(), version.getSizeBytes());
    }

    /**
     * Opens one version's bytes, re-checking access as the caller is NOW.
     * <p>
     * Called by the worker, not only by the request, and that is the point:
     * between a job being queued and it running, the requester may have lost
     * access to the folder, or the document may have been deleted. The check
     * that mattered at request time is not the check that matters at run
     * time.
     *
     * @param versionId the exact version the job recorded - never
     *                  "whatever is current now", which could have moved
     */
    @Transactional
    public DownloadedDocument open(UUID documentId, UUID versionId) {
        Document document = visible(documentId);
        DocumentVersion version = versionRepository
                .findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, documentId, document.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", versionId));
        return versionContent.open(version);
    }

    /** The caller's tenant's document, if their folder access lets them see it. */
    private Document visible(UUID documentId) {
        Document document = documentRepository
                .findByDocumentIdAndTenantIdAndDeletedFalse(documentId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        return documentAccessService.require(documentAccessService.forCaller(), document, FolderAccessLevel.view);
    }

    private DocumentVersion version(Document document, UUID requestedVersion) {
        UUID versionId = requestedVersion != null ? requestedVersion
                : document.getCurrentVersionId() != null ? document.getCurrentVersionId()
                : document.getLatestVersionId();
        if (versionId == null) {
            throw new ResourceNotFoundException("DocumentVersion", document.getDocumentId());
        }
        return versionRepository
                .findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, document.getDocumentId(),
                        document.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", versionId));
    }
}
