package com.oak.dms.document;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.exception.StorageConfigurationException;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.StorageLocationResolver;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.dto.StoredObjectMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * See {@link DocumentService}. Only ever touches storage through
 * {@link ObjectStorageServiceFactory}/{@link StorageLocationResolver} -
 * no AWS SDK, MinIO/SeaweedFS-specific, or {@code java.nio.file} type
 * appears anywhere in this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentServiceImpl implements DocumentService {

    private static final int INITIAL_VERSION_NUMBER = 1;

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentMapper documentMapper;
    private final StorageLocationRepository storageLocationRepository;
    private final StorageLocationResolver storageLocationResolver;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final AuditService auditService;

    @Override
    public DocumentResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                            String contentType, long contentLength, InputStream content) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        StorageLocation location = storageLocationResolver.resolveForTenant(tenantId);

        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String objectKey = buildObjectKey(tenantId, documentId, INITIAL_VERSION_NUMBER, versionId);

        // Upload before persisting anything - fail fast if the storage
        // backend is unreachable/misconfigured, rather than leaving a
        // Document/DocumentVersion row with no actual content behind it.
        // The digest is computed incrementally as upload() reads the
        // stream, so it's ready the moment upload() returns - no separate
        // buffering pass over the whole file.
        MessageDigest digest = sha256();
        StoredObjectMetadata stored = objectStorageServiceFactory.resolve(location)
                .upload(location, objectKey, new DigestInputStream(content, digest), contentLength, contentType);
        String fileHash = HexFormat.of().formatHex(digest.digest());

        DocumentVersion version = DocumentVersion.builder()
                .versionId(versionId)
                .documentId(documentId)
                .tenantId(tenantId)
                .versionNumber(INITIAL_VERSION_NUMBER)
                .majorVersion(1)
                .minorVersion(0)
                .storageLocationId(location.getStorageLocationId())
                .storageObjectKey(objectKey)
                .originalFilename(originalFilename)
                .mimeType(contentType != null ? contentType : "application/octet-stream")
                .fileHash(fileHash)
                .sizeBytes(stored.sizeBytes())
                .createdReason("initial_upload")
                .createdBy(userId)
                .build();

        Document document = Document.builder()
                .documentId(documentId)
                .tenantId(tenantId)
                .documentTypeId(documentTypeId)
                .title(title)
                .currentVersionId(versionId)
                .latestVersionId(versionId)
                .ownerUserId(userId)
                .createdBy(userId)
                .build();

        // Safe in either order - documents<->document_versions FKs are
        // DEFERRABLE INITIALLY DEFERRED (see V32's migration comment).
        Document savedDocument = documentRepository.save(document);
        documentVersionRepository.save(version);

        log.info("Uploaded document {} (version {}) for tenant {}", documentId, versionId, tenantId);

        auditService.event(AuditActionCatalog.DOCUMENT_UPLOADED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(title)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("documentTypeId", documentTypeId, "versionId", versionId,
                        "sizeBytes", stored.sizeBytes()))
                .log();

        return documentMapper.toResponse(savedDocument);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentResponse getDocument(UUID documentId) {
        return documentMapper.toResponse(findOwnedByCallerTenant(documentId));
    }

    @Override
    @Transactional(readOnly = true)
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

    /**
     * Never the raw uploaded filename - always server-generated from UUIDs
     * only, so an object key can never collide, overwrite another object,
     * or be used to traverse/guess another tenant's objects. See
     * docs/storage-architecture.md's object-key section.
     */
    private static String buildObjectKey(UUID tenantId, UUID documentId, int versionNumber, UUID versionId) {
        return "documents/" + tenantId + "/" + documentId + "/v" + versionNumber + "/" + versionId;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }
}
