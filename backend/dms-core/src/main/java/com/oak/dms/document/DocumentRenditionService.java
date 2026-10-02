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
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The browser preview of Office files it can't show itself - Word (.doc),
 * PowerPoint, OpenDocument, RTF and Visio - as a PDF. The first preview of a
 * version converts it (LibreOffice in the Gotenberg container,
 * {@code dms.preview.converter-url}) and keeps the PDF next to the original,
 * encrypted the same way (V64); later previews serve that PDF.
 * <p>
 * The PDF is made from the scanned-clean original only ({@link VersionContent}),
 * never stored anywhere else, and is purged with the document. Access is the
 * document's own: it is a view, audited as DOCUMENT_VIEWED. Files Word, Excel
 * and the browser handle themselves (PDF, .docx, spreadsheets, images, text,
 * email) are rendered in the browser instead.
 */
@Slf4j
@Service
public class DocumentRenditionService {

    /** Extensions converted here - everything else the browser renders itself, or can't be previewed. */
    static final Set<String> CONVERTED = Set.of("doc", "ppt", "pptx", "odt", "odp", "odg", "rtf", "vsd", "vsdx");

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentAccessService documentAccessService;
    private final VersionContent versionContent;
    private final StorageLocationRepository storageLocationRepository;
    private final ObjectStorageServiceFactory storage;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final RestClient converter;
    private final long maxBytes;

    public DocumentRenditionService(DocumentRepository documentRepository, DocumentVersionRepository versionRepository,
                                    DocumentAccessService documentAccessService, VersionContent versionContent,
                                    StorageLocationRepository storageLocationRepository,
                                    ObjectStorageServiceFactory storage, AuditService auditService, JdbcTemplate jdbc,
                                    RestClient.Builder restClients,
                                    @Value("${dms.preview.converter-url:}") String converterUrl,
                                    @Value("${dms.preview.timeout:PT90S}") Duration timeout,
                                    @Value("${dms.preview.max-bytes:52428800}") long maxBytes) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.documentAccessService = documentAccessService;
        this.versionContent = versionContent;
        this.storageLocationRepository = storageLocationRepository;
        this.storage = storage;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.maxBytes = maxBytes;
        if (converterUrl == null || converterUrl.isBlank()) {
            this.converter = null;
        } else {
            SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
            requests.setConnectTimeout(Duration.ofSeconds(5));
            requests.setReadTimeout(timeout);
            this.converter = restClients.clone().baseUrl(converterUrl).requestFactory(requests).build();
        }
    }

    static boolean converted(String filename) {
        return CONVERTED.contains(extension(filename));
    }

    /**
     * The current version as a PDF - converted on first use, served from storage afterwards.
     * Audited as DOCUMENT_VIEWED.
     */
    public DownloadedDocument preview(UUID documentId) {
        Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId,
                        TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        documentAccessService.require(documentAccessService.forCaller(), document, FolderAccessLevel.view);
        UUID versionId = document.getCurrentVersionId() != null ? document.getCurrentVersionId()
                : document.getLatestVersionId();
        DocumentVersion version = versionRepository.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId,
                        documentId, document.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", versionId));
        if (!converted(version.getOriginalFilename())) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "PREVIEW_NOT_CONVERTED",
                    "This file is shown in the browser as it is - there's nothing to convert.");
        }

        DownloadedDocument pdf = version.getRenditionObjectKey() != null
                && "completed".equals(version.getRenditionStatus())
                ? stored(version)
                : convert(version);
        auditService.event(AuditActionCatalog.DOCUMENT_VIEWED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("as", "pdf preview", "versionId", versionId))
                .log();
        return pdf;
    }

    private DownloadedDocument stored(DocumentVersion version) {
        StorageLocation location = location(version);
        var object = storage.resolve(location).download(location, version.getRenditionObjectKey());
        return new DownloadedDocument(object.content(), object.sizeBytes(), MediaType.APPLICATION_PDF_VALUE,
                pdfName(version));
    }

    private DownloadedDocument convert(DocumentVersion version) {
        if (converter == null) {
            throw new FolderException(HttpStatus.SERVICE_UNAVAILABLE, "PREVIEW_UNAVAILABLE",
                    "Previews of this file type aren't set up on this server - download it to open it.");
        }
        if (version.getSizeBytes() > maxBytes) {
            throw new FolderException(HttpStatus.CONTENT_TOO_LARGE, "PREVIEW_TOO_LARGE",
                    "This file is too large to preview - download it to open it.");
        }
        byte[] original;
        try (InputStream in = versionContent.open(version).content()) {
            original = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        byte[] pdf;
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            // Gotenberg picks the converter from the file name's extension; never the user's own name.
            form.add("files", new ByteArrayResource(original) {
                @Override
                public String getFilename() {
                    return "document." + extension(version.getOriginalFilename());
                }
            });
            pdf = converter.post().uri("/forms/libreoffice/convert").contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form).retrieve().body(byte[].class);
        } catch (RestClientException e) {
            log.warn("Could not convert version {} ({}) for preview: {}", version.getVersionId(),
                    extension(version.getOriginalFilename()), e.getMessage());
            mark(version, "failed", null);
            throw new FolderException(HttpStatus.UNPROCESSABLE_CONTENT, "PREVIEW_FAILED",
                    "This file couldn't be turned into a preview - download it to open it.");
        }
        if (pdf == null || pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F') {
            mark(version, "failed", null);
            throw new FolderException(HttpStatus.UNPROCESSABLE_CONTENT, "PREVIEW_FAILED",
                    "This file couldn't be turned into a preview - download it to open it.");
        }

        // Kept beside the original, encrypted like it, so the next preview is instant.
        StorageLocation location = location(version);
        String key = "renditions/" + version.getTenantId() + "/" + version.getDocumentId() + "/"
                + version.getVersionId() + ".pdf";
        storage.resolve(location).upload(location, key, new ByteArrayInputStream(pdf), pdf.length,
                MediaType.APPLICATION_PDF_VALUE);
        mark(version, "completed", key);
        return new DownloadedDocument(new ByteArrayInputStream(pdf), pdf.length, MediaType.APPLICATION_PDF_VALUE,
                pdfName(version));
    }

    private void mark(DocumentVersion version, String status, String key) {
        jdbc.update("update document_versions set rendition_status = ?, rendition_object_key = ?, updated_at = now() "
                + "where version_id = ?", status, key, version.getVersionId());
    }

    private StorageLocation location(DocumentVersion version) {
        return storageLocationRepository.findByStorageLocationIdAndDeletedFalse(version.getStorageLocationId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", version.getVersionId()));
    }

    private static String pdfName(DocumentVersion version) {
        String name = version.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name) + ".pdf";
    }

    static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
