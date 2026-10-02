package com.oak.dms.document;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.exception.StorageConfigurationException;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oak.dms.document.dto.DocumentListItem;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.folder.FolderAccessMode;
import com.oak.dms.folder.FolderAccessService;
import com.oak.dms.folder.FolderService;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * See {@link DocumentService}. Only ever touches storage through
 * {@link ObjectStorageServiceFactory} - no AWS SDK, MinIO/SeaweedFS-specific,
 * or {@code java.nio.file} type appears anywhere in this class.
 * <p>
 * Never creates a {@link DocumentVersion} itself: uploads go through
 * {@link IngestionGate}, and only {@link DocumentVersionPromoter} creates
 * versions, for scanned-clean files.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentMapper documentMapper;
    private final StorageLocationRepository storageLocationRepository;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final IngestionGate ingestionGate;
    private final AuditService auditService;
    private final FolderService folderService;
    private final FolderAccessService folderAccessService;
    private final DocumentAccessService documentAccessService;
    private final com.oak.dms.folder.AccessPrincipals accessPrincipals;
    private final DocumentTypeRepository documentTypeRepository;

    /**
     * Not transactional: the scan gate commits each of its own steps (staging,
     * scanning and promotion must not share one long transaction - and a
     * cloud verdict may arrive long after this returns). The document and its
     * first version are created by {@link DocumentVersionPromoter} only once
     * the file is clean; DOCUMENT_UPLOADED is audited there, at promotion.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IngestionTicketResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                                  long contentLength, InputStream content, UUID folderId) {
        if (folderId != null) {
            // 404/403 now, rather than a document landing nowhere (or somewhere it mustn't) later
            folderService.requireEditable(folderId);
        }
        return ingestionGate.submit(
                IngestionRequest.newDocument(documentTypeId, title, originalFilename, contentLength, content, folderId));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IngestionTicketResponse uploadDocumentFromJob(UUID documentTypeId, String title, String originalFilename,
                                                         long contentLength, InputStream content, UUID folderId) {
        if (folderId != null) {
            folderService.requireLive(folderId);
        }
        return ingestionGate.submit(
                IngestionRequest.newDocument(documentTypeId, title, originalFilename, contentLength, content, folderId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DocumentListItem> listDocuments(UUID folderId, boolean allFolders, String query, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        String q = query == null || query.isBlank() ? null : query.strip();
        DocumentAccessView access = documentAccessService.forCaller();
        if (folderId != null && !allFolders) {
            folderAccessService.require(access.folders(), folderId, FolderAccessLevel.view);
        }
        Page<Document> page;
        if (!access.seesEverything()) {
            // Folder access (V53) and document access (V54) both apply.
            String title = q == null ? "" : q;
            page = allFolders
                    ? documentRepository.findVisible(tenantId, title, nonEmpty(access.folders().hiddenFolderIds()),
                    access.grantedIds(), pageable)
                    : folderId != null
                    ? documentRepository.findVisibleInFolder(tenantId, title, folderId, access.grantedIds(), pageable)
                    : documentRepository.findVisibleAtTopLevel(tenantId, title, access.grantedIds(), pageable);
        } else if (allFolders) {
            page = q == null ? documentRepository.findByTenantIdAndDeletedFalse(tenantId, pageable)
                    : documentRepository.findByTenantIdAndDeletedFalseAndTitleContainingIgnoreCase(tenantId, q, pageable);
        } else if (folderId != null) {
            page = q == null ? documentRepository.findByTenantIdAndFolderIdAndDeletedFalse(tenantId, folderId, pageable)
                    : documentRepository.findByTenantIdAndFolderIdAndDeletedFalseAndTitleContainingIgnoreCase(tenantId,
                    folderId, q, pageable);
        } else {
            page = q == null ? documentRepository.findByTenantIdAndFolderIdIsNullAndDeletedFalse(tenantId, pageable)
                    : documentRepository.findByTenantIdAndFolderIdIsNullAndDeletedFalseAndTitleContainingIgnoreCase(
                    tenantId, q, pageable);
        }

        return toItems(page, access);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DocumentListItem> listSharedWithMe(String query, Pageable pageable) {
        DocumentAccessView access = documentAccessService.forCaller();
        String title = query == null ? "" : query.strip();
        Page<Document> page = documentRepository.findSharedWith(TenantContext.getTenantId(), title,
                TenantContext.getUserId(), accessPrincipals.callerRoleIds(), pageable);
        return toItems(page, access);
    }

    /** List rows for a page of documents: current version, type name, and the caller's access. */
    private Page<DocumentListItem> toItems(Page<Document> page, DocumentAccessView access) {
        UUID tenantId = TenantContext.getTenantId();
        List<UUID> versionIds = page.getContent().stream()
                .map(d -> d.getCurrentVersionId() != null ? d.getCurrentVersionId() : d.getLatestVersionId())
                .toList();
        Map<UUID, DocumentVersion> versions = new HashMap<>();
        documentVersionRepository.findAllById(versionIds).stream()
                .filter(v -> tenantId.equals(v.getTenantId()))
                .forEach(v -> versions.put(v.getVersionId(), v));
        Map<UUID, String> typeNames = new HashMap<>();
        documentTypeRepository.findAllById(page.getContent().stream().map(Document::getDocumentTypeId).distinct().toList())
                .forEach(t -> typeNames.put(t.getDocumentTypeId(), t.getTypeName()));

        return page.map(d -> {
            DocumentVersion v = versions.get(d.getCurrentVersionId() != null ? d.getCurrentVersionId() : d.getLatestVersionId());
            return new DocumentListItem(d.getDocumentId(), d.getTitle(), d.getDocumentTypeId(),
                    typeNames.get(d.getDocumentTypeId()), d.getFolderId(),
                    v != null ? v.getOriginalFilename() : null, v != null ? v.getSizeBytes() : null,
                    v != null ? v.getMimeType() : null, v != null ? v.getVersionNumber() : null,
                    d.getOwnerUserId(), d.getCreatedAt(), d.getAccessMode() == FolderAccessMode.restricted,
                    access.level(d).name());
        });
    }

    @Override
    public int moveDocuments(List<UUID> documentIds, UUID folderId) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentAccessView access = documentAccessService.forCaller();
        String folderName = folderId == null ? null
                : folderAccessService.require(access.folders(), folderId, FolderAccessLevel.edit).getFolderName();
        Set<UUID> wanted = new LinkedHashSet<>(documentIds);
        List<Document> documents = documentRepository.findAllByDocumentIdInAndTenantIdAndDeletedFalse(wanted, tenantId);
        // A document hidden from the caller counts as not found, like any other.
        if (documents.size() != wanted.size() || documents.stream().anyMatch(d -> !access.canSee(d))) {
            throw new ResourceNotFoundException("Document", "one or more of the selected documents");
        }
        // Moving needs edit on the document - for one that follows its folder, edit in that folder.
        for (Document document : documents) {
            documentAccessService.require(access, document, FolderAccessLevel.edit);
        }
        int moved = 0;
        for (Document document : documents) {
            if (Objects.equals(document.getFolderId(), folderId)) {
                continue;
            }
            UUID from = document.getFolderId();
            document.setFolderId(folderId);
            document.setUpdatedBy(TenantContext.getUserId());
            document.setUpdatedAt(OffsetDateTime.now());
            documentRepository.save(document);
            moved++;
            Map<String, Object> before = new HashMap<>();
            before.put("folderId", from);
            Map<String, Object> after = new HashMap<>();
            after.put("folderId", folderId);
            after.put("folderName", folderName);
            auditService.event(AuditActionCatalog.DOCUMENT_MOVED)
                    .objectType(AuditObjectTypes.DOCUMENT)
                    .objectId(document.getDocumentId())
                    .resourceName(document.getTitle())
                    .outcome(AuditOutcome.SUCCESS)
                    .previousValue(before)
                    .newValue(after)
                    .log();
        }
        return moved;
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentResponse getDocument(UUID documentId) {
        return documentMapper.toResponse(findVisible(documentId, FolderAccessLevel.view));
    }

    /**
     * Read-write on purpose: the DOCUMENT_DOWNLOADED (or DOCUMENT_VIEWED) audit
     * row joins this transaction and is only written when it commits - a
     * read-only transaction silently dropped it.
     */
    @Override
    @Transactional
    public DownloadedDocument downloadDocument(UUID documentId) {
        return serve(findVisible(documentId, FolderAccessLevel.view), AuditActionCatalog.DOCUMENT_DOWNLOADED, null);
    }

    @Override
    @Transactional
    public DownloadedDocument previewDocument(UUID documentId) {
        return serve(findVisible(documentId, FolderAccessLevel.view), AuditActionCatalog.DOCUMENT_VIEWED, null);
    }

    @Override
    @Transactional
    public DownloadedDocument downloadDocumentFromJob(UUID documentId, String job) {
        return serve(findOwnedByCallerTenant(documentId), AuditActionCatalog.DOCUMENT_DOWNLOADED, job);
    }

    /** The document's current (or latest) servable version, audited as {@code action}. */
    private DownloadedDocument serve(Document document, String action, String job) {
        UUID tenantId = TenantContext.getTenantId();
        UUID documentId = document.getDocumentId();

        UUID versionId = document.getCurrentVersionId() != null
                ? document.getCurrentVersionId()
                : document.getLatestVersionId();
        DocumentVersion version = documentVersionRepository
                .findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", versionId));

        StorageLocation location = storageLocationRepository
                .findByStorageLocationIdAndDeletedFalse(version.getStorageLocationId())
                .orElseThrow(() -> new StorageConfigurationException(
                        "Storage location " + version.getStorageLocationId() + " referenced by version "
                                + versionId + " no longer exists"));
        // Defense in depth - the object key is never the sole tenant-isolation
        // boundary. A platform-default location (tenantId null) is fine for
        // any tenant; a tenant-owned location must match the caller's own.
        if (location.getTenantId() != null && !location.getTenantId().equals(tenantId)) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        // Scan gate, read side. Only document-purpose locations ever back a
        // version (the V39 trigger guarantees it; checked again here), so
        // staging and quarantine are unreachable through this path. And only
        // scanned-clean bytes are served - legacy versions uploaded before
        // the gate existed are refused. An administrator's quarantine
        // override ('overridden', V40) is servable by design.
        if (location.getPurpose() != StoragePurpose.document) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        if (!version.isServable()) {
            throw new DocumentNotScannedException(documentId);
        }

        DownloadedObject downloaded = objectStorageServiceFactory.resolve(location)
                .download(location, version.getStorageObjectKey());

        var event = auditService.event(action)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS);
        if (job != null) {
            event.newValue(Map.of("via", job));
        }
        event.log();

        // mimeType/sizeBytes come from DocumentVersion (the authoritative
        // record), not downloaded's own best-effort values - see
        // DownloadedDocument's own doc comment.
        return new DownloadedDocument(downloaded.content(), version.getSizeBytes(), version.getMimeType(),
                version.getOriginalFilename());
    }

    @Override
    public void deleteDocument(UUID documentId) {
        Document document = findVisible(documentId, FolderAccessLevel.edit);

        document.setDeleted(true);
        document.setDeletedAt(OffsetDateTime.now());
        document.setUpdatedBy(TenantContext.getUserId());
        document.setUpdatedAt(OffsetDateTime.now());
        documentRepository.save(document);

        log.info("Deleted document {}", documentId);

        // Soft delete only - the physical object is deliberately left in
        // place (audit-trail/legal-hold concerns argue against purging it
        // on every soft delete). Physical purge is a documented limitation,
        // not wired up in this pass - see docs/storage-architecture.md.
        auditService.event(AuditActionCatalog.DOCUMENT_DELETED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .log();
    }

    private Document findOwnedByCallerTenant(UUID documentId) {
        return documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
    }

    /**
     * The caller's tenant's document, if its folder lets them in at {@code needed}:
     * a document hidden from them (its folder or its own list, V53/V54) is 404, like one that doesn't exist.
     */
    private Document findVisible(UUID documentId, FolderAccessLevel needed) {
        return documentAccessService.require(documentAccessService.forCaller(), findOwnedByCallerTenant(documentId),
                needed);
    }

    /** For a NOT IN clause, which must never be empty. */
    private static Set<UUID> nonEmpty(Set<UUID> ids) {
        return ids.isEmpty() ? Set.of(new UUID(0, 0)) : ids;
    }
}
