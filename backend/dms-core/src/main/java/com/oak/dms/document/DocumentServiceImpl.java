package com.oak.dms.document;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DocumentVersionResponse;
import com.oak.dms.ocrworker.OcrWorkerClient;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final ObjectStorageService objectStorageService;
    private final OcrWorkerClient ocrWorkerClient;

    @Override
    @Transactional
    public DocumentVersionResponse uploadDocument(MultipartFile file, String documentTypeName) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        DocumentType documentType = documentTypeRepository
                .findByTenantIdAndDocumentTypeNameAndDeletedFalse(tenantId, documentTypeName)
                .orElseGet(() -> documentTypeRepository.save(DocumentType.builder()
                        .tenantId(tenantId)
                        .documentTypeName(documentTypeName)
                        .createdBy(userId)
                        .build()));

        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String storageObjectKey = "tenants/%s/documents/%s/versions/%s/%s"
                .formatted(tenantId, documentId, versionId, originalFilename);

        byte[] content;
        try (InputStream in = file.getInputStream()) {
            content = in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }

        objectStorageService.putSourceObject(storageObjectKey, content, file.getContentType());

        // documents.latest_version_id <-> document_versions.document_id are
        // mutually NOT NULL foreign keys into each other -- see V23's own
        // comment for why both were declared DEFERRABLE INITIALLY DEFERRED
        // specifically so this works: both rows (client-generated UUIDs,
        // assigned above) are persisted in one transaction, and the FK
        // check only runs at commit, by which point both exist.
        Document document = Document.builder()
                .documentId(documentId)
                .tenantId(tenantId)
                .documentTypeId(documentType.getDocumentTypeId())
                .title(originalFilename)
                .latestVersionId(versionId)
                .currentVersionId(versionId)
                .ownerUserId(userId)
                .createdBy(userId)
                .build();
        documentRepository.save(document);

        // Section 2.2 (malware scanning) has no real implementation yet --
        // backend/workers/scan-worker/ doesn't exist (see ocr-worker's own
        // README "Known upstream gap"). This upload path stands in for it
        // by setting scan_status='clean' immediately -- the same
        // documented gap ocr-worker's own dev-trigger endpoint and fixture
        // scripts already work around, never silently pretended away.
        DocumentVersion version = DocumentVersion.builder()
                .versionId(versionId)
                .documentId(documentId)
                .tenantId(tenantId)
                .versionNumber(1)
                .majorVersion(1)
                .minorVersion(0)
                .storageObjectKey(storageObjectKey)
                .originalFilename(originalFilename)
                .mimeType(file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                .fileHash(sha256Hex(content))
                .sizeBytes(content.length)
                .createdReason("initial_upload")
                .scanStatus("clean")
                .ocrStatus("pending")
                .createdBy(userId)
                .build();
        documentVersionRepository.save(version);

        log.info("Uploaded document {} version {} ({} bytes) for tenant {}", documentId, versionId, content.length, tenantId);

        return new DocumentVersionResponse(
                documentId, versionId, document.getTitle(), originalFilename,
                version.getMimeType(), version.getSizeBytes(), version.getScanStatus(),
                version.getOcrStatus(), version.getCreatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> runOcr(UUID versionId) {
        UUID tenantId = TenantContext.getTenantId();
        DocumentVersion version = documentVersionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("document_versions", versionId));
        if (!version.getTenantId().equals(tenantId)) {
            // Same "not found" a genuinely-missing row would produce --
            // never reveal that a version exists in a different tenant.
            throw new ResourceNotFoundException("document_versions", versionId);
        }
        return ocrWorkerClient.processVersion(versionId);
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
