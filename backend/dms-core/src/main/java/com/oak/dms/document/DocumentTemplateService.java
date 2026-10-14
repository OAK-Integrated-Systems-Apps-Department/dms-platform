package com.oak.dms.document;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.folder.FolderException;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.IngestionKind;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Document templates (V69): ready-made files a document type offers - a blank contract in
 * Word, a proposal in PowerPoint, a form as PDF - for people to download, fill in and upload,
 * or to start a new document from in a folder.
 * <p>
 * A template is a document flagged {@code is_template}, so it is virus-scanned (any engine,
 * cloud verdicts included), encrypted, versioned, previewed and downloaded exactly like one -
 * but it is listed only here, and changed only here: everywhere else it is view-only
 * ({@link com.oak.dms.folder.DocumentAccessService}). Adding, replacing, renaming and removing
 * need the document-type update permission (checked by the controller).
 */
@Service
@RequiredArgsConstructor
public class DocumentTemplateService {

    /** created_reason of a template's first version - and what marks the document a template when it's promoted. */
    public static final String CREATED_REASON = "template";

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentService documentService;
    private final IngestionGate ingestionGate;
    private final AuditService auditService;

    /**
     * @param active whether its document type is active - only those are offered to start a document from
     */
    public record TemplateItem(UUID templateId, UUID documentTypeId, String documentTypeName, boolean active,
                               String name, String fileName, String mimeType, Long sizeBytes, String versionLabel,
                               OffsetDateTime updatedAt) {
    }

    /** Every template, or one document type's - by name. */
    @Transactional(readOnly = true)
    public List<TemplateItem> list(UUID documentTypeId) {
        UUID tenantId = TenantContext.getTenantId();
        List<Document> templates = documentTypeId == null
                ? documentRepository.findByTenantIdAndTemplateTrueAndDeletedFalseOrderByTitleAsc(tenantId)
                : documentRepository.findByTenantIdAndDocumentTypeIdAndTemplateTrueAndDeletedFalseOrderByTitleAsc(
                tenantId, documentTypeId);
        Set<UUID> typeIds = templates.stream().map(Document::getDocumentTypeId).collect(Collectors.toSet());
        Map<UUID, DocumentType> types = documentTypeRepository.findAllById(typeIds).stream()
                .filter(type -> tenantId.equals(type.getTenantId()) && !type.isDeleted())
                .collect(Collectors.toMap(DocumentType::getDocumentTypeId, Function.identity()));
        return templates.stream()
                .filter(template -> types.containsKey(template.getDocumentTypeId()))
                .map(template -> {
                    DocumentType type = types.get(template.getDocumentTypeId());
                    DocumentVersion version = currentVersion(template);
                    return new TemplateItem(template.getDocumentId(), type.getDocumentTypeId(), type.getTypeName(),
                            type.getStatus() == DocumentType.DocumentTypeStatus.active, template.getTitle(),
                            version != null ? version.getOriginalFilename() : null,
                            version != null ? version.getMimeType() : null,
                            version != null ? version.getSizeBytes() : null,
                            version != null ? version.getVersionLabel() : null,
                            version != null ? version.getCreatedAt() : template.getCreatedAt());
                })
                .toList();
    }

    /** Adds a template to a document type - scanned like any upload before it appears. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IngestionTicketResponse add(UUID documentTypeId, String name, String originalFilename, long contentLength,
                                       InputStream content) {
        documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId));
        String title = name(name, originalFilename);
        return ingestionGate.submit(new IngestionRequest(IngestionKind.new_document, CREATED_REASON, documentTypeId,
                null, title, originalFilename, contentLength, content, null, null, false));
    }

    /** A new file for the template - its old one stays in the version history. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IngestionTicketResponse replace(UUID templateId, String originalFilename, long contentLength,
                                           InputStream content) {
        find(templateId);
        return ingestionGate.submit(IngestionRequest.newVersion(templateId, "replacement_upload", originalFilename,
                contentLength, content, "Template replaced"));
    }

    @Transactional
    public TemplateItem rename(UUID templateId, String name) {
        Document template = find(templateId);
        String before = template.getTitle();
        template.setTitle(name(name, null));
        template.setUpdatedBy(TenantContext.getUserId());
        template.setUpdatedAt(OffsetDateTime.now());
        documentRepository.save(template);
        auditService.event(AuditActionCatalog.DOCUMENT_TYPE_UPDATED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(templateId)
                .resourceName(template.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of("template", before))
                .newValue(Map.of("template", template.getTitle()))
                .log();
        return list(template.getDocumentTypeId()).stream()
                .filter(item -> item.templateId().equals(templateId)).findFirst().orElseThrow();
    }

    /** No longer offered. Its files are destroyed when the recycle-bin retention period ends, as for any document. */
    @Transactional
    public void remove(UUID templateId) {
        Document template = find(templateId);
        template.setDeleted(true);
        template.setDeletedAt(OffsetDateTime.now());
        template.setUpdatedBy(TenantContext.getUserId());
        template.setUpdatedAt(OffsetDateTime.now());
        documentRepository.save(template);
        auditService.event(AuditActionCatalog.DOCUMENT_DELETED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(templateId)
                .resourceName(template.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("template", true))
                .log();
    }

    /**
     * A new document in {@code folderId} (null = the top level) starting from the template's file,
     * of the template's document type. Needs edit access to the folder; scanned like any upload.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IngestionTicketResponse copy(UUID templateId, UUID folderId, String title) {
        Document template = find(templateId);
        DocumentType type = documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(template.getDocumentTypeId(), TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", template.getDocumentTypeId()));
        if (type.getStatus() != DocumentType.DocumentTypeStatus.active) {
            throw new FolderException(HttpStatus.CONFLICT, "DOCUMENT_TYPE_INACTIVE",
                    "\"" + type.getTypeName() + "\" isn't in use any more, so new documents can't be made from its templates.");
        }
        DownloadedDocument file = documentService.downloadDocument(templateId);
        try (InputStream content = file.content()) {
            return documentService.uploadDocument(template.getDocumentTypeId(),
                    title == null || title.isBlank() ? template.getTitle() : name(title, null),
                    file.filename(), file.sizeBytes(), content, folderId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Document find(UUID templateId) {
        return documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(templateId, TenantContext.getTenantId())
                .filter(Document::isTemplate)
                .orElseThrow(() -> new ResourceNotFoundException("Template", templateId));
    }

    private DocumentVersion currentVersion(Document document) {
        UUID versionId = document.getCurrentVersionId() != null ? document.getCurrentVersionId()
                : document.getLatestVersionId();
        return versionId == null ? null : documentVersionRepository
                .findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, document.getDocumentId(),
                        document.getTenantId())
                .orElse(null);
    }

    /** The given name, trimmed - or the file's name without its extension. */
    private static String name(String name, String originalFilename) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() && originalFilename != null) {
            String file = originalFilename.replace('\\', '/');
            file = file.substring(file.lastIndexOf('/') + 1);
            int dot = file.lastIndexOf('.');
            clean = (dot > 0 ? file.substring(0, dot) : file).strip();
        }
        if (clean.isEmpty()) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "TEMPLATE_NAME_INVALID", "Give the template a name.");
        }
        if (clean.length() > 255) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "TEMPLATE_NAME_INVALID", "A template name can be at most 255 characters.");
        }
        return clean;
    }
}
