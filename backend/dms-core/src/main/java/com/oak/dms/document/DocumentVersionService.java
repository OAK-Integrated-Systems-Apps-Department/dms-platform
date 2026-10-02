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
                              String createdByName, boolean current) {
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
                names.get(v.getCreatedBy()), v.getVersionId().equals(current))).toList();
    }

    /** One version's file. Audited as DOCUMENT_DOWNLOADED, naming the version. */
    @Transactional
    public DownloadedDocument download(UUID documentId, UUID versionId) {
        Document document = visible(documentId, FolderAccessLevel.view);
        DocumentVersion version = version(document, versionId);
        DownloadedDocument downloaded = versionContent.open(version);
        auditService.event(AuditActionCatalog.DOCUMENT_DOWNLOADED)
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
        visible(documentId, FolderAccessLevel.edit);
        return ingestionGate.submit(IngestionRequest.newVersion(documentId, "replacement_upload", originalFilename,
                contentLength, content, clean(comment)));
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
            ticket = ingestionGate.submit(IngestionRequest.newVersion(documentId, "rollback",
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
