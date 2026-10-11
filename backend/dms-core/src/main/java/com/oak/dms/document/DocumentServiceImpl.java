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

import java.io.InputStream;
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
                                                  long contentLength, InputStream content) {
        return ingestionGate.submit(
                IngestionRequest.newDocument(documentTypeId, title, originalFilename, contentLength, content));
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentResponse getDocument(UUID documentId) {
        return documentMapper.toResponse(findOwnedByCallerTenant(documentId));
    }

    // NOT readOnly: the DOCUMENT_DOWNLOADED audit row must be written. That
    // write joins this transaction (see AuditService#persist's
    // @Transactional), and a readOnly transaction never flushes it.
    @Override
    @Transactional
    public DownloadedDocument downloadDocument(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = findOwnedByCallerTenant(documentId);

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

        auditService.event(AuditActionCatalog.DOCUMENT_DOWNLOADED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .log();

        // mimeType/sizeBytes come from DocumentVersion (the authoritative
        // record), not downloaded's own best-effort values - see
        // DownloadedDocument's own doc comment.
        return new DownloadedDocument(downloaded.content(), version.getSizeBytes(), version.getMimeType(),
                version.getOriginalFilename());
    }

    @Override
    public void deleteDocument(UUID documentId) {
        Document document = findOwnedByCallerTenant(documentId);

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
}
