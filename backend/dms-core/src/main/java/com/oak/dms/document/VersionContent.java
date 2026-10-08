package com.oak.dms.document;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.exception.StorageConfigurationException;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Opens one version's stored bytes - the read side of the scan gate, shared
 * by downloads of the current version, of an older one, and a restore. Access
 * to the document is the caller's job; this checks the storage side: the
 * location is the tenant's (or the platform's) and holds documents, and the
 * version was scanned clean or released by an administrator.
 */
@Component
@RequiredArgsConstructor
class VersionContent {

    private final StorageLocationRepository storageLocationRepository;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;

    DownloadedDocument open(DocumentVersion version) {
        UUID tenantId = TenantContext.getTenantId();
        UUID documentId = version.getDocumentId();
        if (version.getPurgedAt() != null) {
            throw new ResourceNotFoundException("DocumentVersion", version.getVersionId());
        }
        StorageLocation location = storageLocationRepository
                .findByStorageLocationIdAndDeletedFalse(version.getStorageLocationId())
                .orElseThrow(() -> new StorageConfigurationException(
                        "Storage location " + version.getStorageLocationId() + " referenced by version "
                                + version.getVersionId() + " no longer exists"));
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
        // mimeType/sizeBytes come from DocumentVersion (the authoritative
        // record), not downloaded's own best-effort values - see
        // DownloadedDocument's own doc comment.
        return new DownloadedDocument(downloaded.content(), version.getSizeBytes(), version.getMimeType(),
                version.getOriginalFilename());
    }
}
