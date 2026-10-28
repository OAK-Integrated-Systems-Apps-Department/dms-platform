package com.oak.dms.document;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.folder.FolderException;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A document's version history (module DVC): the list, any version's file,
 * a new version, and restoring an older one.
 * <p>
 * A new version goes through the malware scan gate like any upload, and
 * becomes the document's current version once it is clean
 * ({@link DocumentVersionPromoter}). Restoring doesn't move a pointer back:
 * the older version's bytes are submitted again as a new {@code rollback}
 * version, so the history only ever grows and every version stays as it was.
 * Access is the document's own (V53/V54): viewing the history needs view,
 * changing it needs edit.
 */
@Service
@RequiredArgsConstructor
public class DocumentVersionService {

    /** created_reason of a version uploaded over an existing document - what {@link #upload} sends through the gate. */
    public static final String REPLACEMENT_UPLOAD = "replacement_upload";
    /** created_reason of a version made by putting an older one back - what {@link #restore} sends through the gate. */
    public static final String ROLLBACK = "rollback";

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentAccessService documentAccessService;
    private final IngestionGate ingestionGate;
    private final VersionContent versionContent;
    private final UserRepository userRepository;
    private final AuditService auditService;

    /**
     * One row of the history.
     *
     * @param current whether this is the version that opens and downloads
     */
    public record VersionItem(UUID versionId, int versionNumber, String versionLabel, String originalFilename,
                              String mimeType, long sizeBytes, String versionComment, String createdReason,
                              String scanStatus, boolean servable, OffsetDateTime createdAt, UUID createdBy,
                              String createdByName, boolean current, String fileHash) {
    }

    @Transactional(readOnly = true)
    public List<VersionItem> list(UUID documentId) {
        Document document = visible(documentId, FolderAccessLevel.view);
        List<DocumentVersion> versions = versionRepository
                .findByDocumentIdAndTenantIdAndDeletedFalseOrderByVersionNumberDesc(documentId, document.getTenantId());
        Set<UUID> authors = versions.stream().map(DocumentVersion::getCreatedBy).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<UUID, String> names = userRepository.findAllById(authors).stream()
                .filter(u -> document.getTenantId().equals(u.getTenantId()))
                .collect(Collectors.toMap(User::getUserId, u -> u.getDisplayName() != null ? u.getDisplayName()
                        : u.getEmail(), (a, b) -> a));
        UUID current = currentVersionId(document);
        return versions.stream().map(v -> new VersionItem(v.getVersionId(), v.getVersionNumber(), label(v),
                v.getOriginalFilename(), v.getMimeType(), v.getSizeBytes(), v.getVersionComment(),
                v.getCreatedReason(), v.getScanStatus(), v.isServable(), v.getCreatedAt(), v.getCreatedBy(),
                names.get(v.getCreatedBy()), v.getVersionId().equals(current), v.getFileHash())).toList();
    }

    /** One version's file. Audited as DOCUMENT_DOWNLOADED, naming the version. */
    @Transactional
    public DownloadedDocument download(UUID documentId, UUID versionId) {
        return open(documentId, versionId, AuditActionCatalog.DOCUMENT_DOWNLOADED);
    }

    /** One version's file, for the in-app preview. Audited as DOCUMENT_VIEWED, naming the version. */
    @Transactional
    public DownloadedDocument preview(UUID documentId, UUID versionId) {
        return open(documentId, versionId, AuditActionCatalog.DOCUMENT_VIEWED);
    }

    private DownloadedDocument open(UUID documentId, UUID versionId, String action) {
        Document document = visible(documentId, FolderAccessLevel.view);
        DocumentVersion version = version(document, versionId);
        DownloadedDocument downloaded = versionContent.open(version);
        auditService.event(action)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("versionId", versionId, "version", label(version)))
                .log();
        return downloaded;
    }

    /**
     * A new version - scanned first, like any upload; it becomes current once clean.
     *
     * @param comment what changed; may be blank
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IngestionTicketResponse upload(UUID documentId, String originalFilename, long contentLength,
                                          InputStream content, String comment) {
        return upload(documentId, originalFilename, contentLength, content, comment, null, false);
    }

    /**
     * As {@link #upload(UUID, String, String, long, InputStream, String)}, with an OCR field
     * selection for the new version.
     *
     * @param requestedFields field keys for OCR to extract, or null/empty for no OCR. Queued by
     *                        {@link DocumentVersionPromoter} only after a clean verdict - a new
     *                        version is a new set of bytes, so it gets its own OCR run rather
     *                        than inheriting the previous version's results.
     *                        <p>
     *                        <b>Taken as given.</b> Unlike {@code DocumentServiceImpl#withOcr},
     *                        this does not re-check the keys against the document type's schema,
     *                        because its only caller - {@code ImportRecordCreator} - validated
     *                        the batch's selection once at batch creation
     *                        ({@code BulkImportServiceImpl#ocrFieldsJson}) and every row of the
     *                        batch shares it. A new caller that has NOT already validated must
     *                        validate before calling, or an unknown key reaches ocr-worker and
     *                        silently extracts nothing.
     * @param lowPriority     true for bulk import, which routes to the separate low-priority
     *                        queue so an import cannot starve an interactive scan
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IngestionTicketResponse upload(UUID documentId, String originalFilename, long contentLength,
                                          InputStream content, String comment,
                                          List<String> requestedFields, boolean lowPriority) {
        visible(documentId, FolderAccessLevel.edit);
        IngestionRequest request = IngestionRequest.newVersion(documentId, REPLACEMENT_UPLOAD, originalFilename,
                contentLength, content, clean(comment));
        return ingestionGate.submit(
                requestedFields == null || requestedFields.isEmpty()
                        ? request
                        : request.withOcr(requestedFields, lowPriority));
    }

    /**
     * Makes an older version current again - as a new version with its bytes (re-scanned, like any
     * upload), so nothing in the history is rewritten. Audited as DOCUMENT_VERSION_RESTORED.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IngestionTicketResponse restore(UUID documentId, UUID versionId) {
        Document document = visible(documentId, FolderAccessLevel.edit);
        DocumentVersion version = version(document, versionId);
        if (version.getVersionId().equals(currentVersionId(document))) {
            throw new FolderException(HttpStatus.CONFLICT, "VERSION_ALREADY_CURRENT",
                    "Version " + label(version) + " is already the current version.");
        }
        DownloadedDocument old = versionContent.open(version);
        IngestionTicketResponse ticket;
        try (InputStream content = old.content()) {
            ticket = ingestionGate.submit(IngestionRequest.newVersion(documentId, ROLLBACK,
                    version.getOriginalFilename(), version.getSizeBytes(), content,
                    "Restored from version " + label(version)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        auditService.event(AuditActionCatalog.DOCUMENT_VERSION_RESTORED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("restoredVersionId", versionId, "restoredVersion", label(version),
                        "scanResultId", ticket.scanResultId()))
                .log();
        return ticket;
    }

    private Document visible(UUID documentId, FolderAccessLevel needed) {
        Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId,
                        TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        return documentAccessService.require(documentAccessService.forCaller(), document, needed);
    }

    private DocumentVersion version(Document document, UUID versionId) {
        return versionRepository.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId,
                        document.getDocumentId(), document.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", versionId));
    }

    private static UUID currentVersionId(Document document) {
        return document.getCurrentVersionId() != null ? document.getCurrentVersionId() : document.getLatestVersionId();
    }

    /** "1.2" - versions from before labels were stored get theirs from major/minor. */
    static String label(DocumentVersion version) {
        return version.getVersionLabel() != null ? version.getVersionLabel()
                : version.getMajorVersion() + "." + version.getMinorVersion();
    }

    private static String clean(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.trim();
        return trimmed.length() > 1000 ? trimmed.substring(0, 1000) : trimmed;
    }
}
