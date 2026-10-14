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
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * <p>
 * Presentations (.ppt, .pptx, .odp) are shown as slides: the same PDF, one page
 * per slide, drawn as images ({@link #slides}, {@link #slide}) so the viewer can
 * step through them like a slide show.
 */
@Slf4j
@Service
public class DocumentRenditionService {

    /** Extensions converted here - everything else the browser renders itself, or can't be previewed. */
    static final Set<String> CONVERTED = Set.of("doc", "ppt", "pptx", "odt", "odp", "odg", "rtf", "vsd", "vsdx");
    /** Of those, the presentations - shown slide by slide. */
    static final Set<String> SLIDES = Set.of("ppt", "pptx", "odp");

    /** What the slide viewer needs first: which version, how many slides, and their shape (points). */
    public record SlideDeck(UUID versionId, int slides, float width, float height) {
    }

    /**
     * The converted PDFs of the last few decks opened, so stepping through slides doesn't fetch
     * and decrypt the PDF again for every one. Only PDFs up to {@link #CACHED_PDF_MAX_BYTES}.
     */
    private static final int CACHED_PDFS = 4;
    private static final int CACHED_PDF_MAX_BYTES = 64 * 1024 * 1024;
    private final Map<UUID, byte[]> recentPdfs = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHED_PDFS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, byte[]> eldest) {
                    return size() > CACHED_PDFS;
                }
            });

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

    static boolean slides(String filename) {
        return SLIDES.contains(extension(filename));
    }

    /**
     * The current version as a PDF - converted on first use, served from storage afterwards.
     * Audited as DOCUMENT_VIEWED.
     */
    public DownloadedDocument preview(UUID documentId) {
        return preview(documentId, null);
    }

    /** @param requestedVersion one of its versions (from the version history); null = the current one */
    public DownloadedDocument preview(UUID documentId, UUID requestedVersion) {
        Viewed viewed = viewable(documentId, requestedVersion);
        DownloadedDocument pdf = pdfOf(viewed.version());
        audit(viewed, "pdf preview");
        return pdf;
    }

    /**
     * A presentation, ready to be shown slide by slide: converted on first use like
     * {@link #preview}, and audited as DOCUMENT_VIEWED once - here, not for every slide.
     *
     * @param requestedVersion one of its versions; null = the current one
     */
    public SlideDeck slides(UUID documentId, UUID requestedVersion) {
        Viewed viewed = viewable(documentId, requestedVersion);
        requireSlides(viewed.version());
        SlidePages.Deck deck = SlidePages.describe(pdfBytes(viewed.version()));
        audit(viewed, "slides");
        return new SlideDeck(viewed.version().getVersionId(), deck.slides(), deck.width(), deck.height());
    }

    /**
     * Slide {@code number} (from 1) as a JPEG about {@code width} pixels wide. Access is checked
     * for every slide; the view itself was audited when the deck was opened ({@link #slides}).
     */
    public byte[] slide(UUID documentId, UUID requestedVersion, int number, int width) {
        Viewed viewed = viewable(documentId, requestedVersion);
        requireSlides(viewed.version());
        try {
            return SlidePages.render(pdfBytes(viewed.version()), number - 1, width);
        } catch (IndexOutOfBoundsException e) {
            throw new ResourceNotFoundException("Slide", number);
        }
    }

    private record Viewed(Document document, DocumentVersion version) {
    }

    /** The document (which the caller must be able to view) and the version to show, if it's converted. */
    private Viewed viewable(UUID documentId, UUID requestedVersion) {
        Document document = documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId,
                        TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        documentAccessService.require(documentAccessService.forCaller(), document, FolderAccessLevel.view);
        UUID versionId = requestedVersion != null ? requestedVersion
                : document.getCurrentVersionId() != null ? document.getCurrentVersionId()
                : document.getLatestVersionId();
        DocumentVersion version = versionRepository.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId,
                        documentId, document.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", versionId));
        if (!converted(version.getOriginalFilename())) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "PREVIEW_NOT_CONVERTED",
                    "This file is shown in the browser as it is - there's nothing to convert.");
        }
        return new Viewed(document, version);
    }

    private static void requireSlides(DocumentVersion version) {
        if (!slides(version.getOriginalFilename())) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "PREVIEW_NOT_SLIDES",
                    "Only presentations are shown as slides.");
        }
    }

    private DownloadedDocument pdfOf(DocumentVersion version) {
        return version.getRenditionObjectKey() != null && "completed".equals(version.getRenditionStatus())
                ? stored(version)
                : convert(version);
    }

    /** The version's PDF in memory - from the recent ones, or stored/converted. */
    private byte[] pdfBytes(DocumentVersion version) {
        byte[] cached = recentPdfs.get(version.getVersionId());
        if (cached != null) {
            return cached;
        }
        byte[] pdf;
        try (InputStream in = pdfOf(version).content()) {
            pdf = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (pdf.length <= CACHED_PDF_MAX_BYTES) {
            recentPdfs.put(version.getVersionId(), pdf);
        }
        return pdf;
    }

    private void audit(Viewed viewed, String as) {
        auditService.event(AuditActionCatalog.DOCUMENT_VIEWED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(viewed.document().getDocumentId())
                .resourceName(viewed.document().getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("as", as, "versionId", viewed.version().getVersionId()))
                .log();
    }

    private DownloadedDocument stored(DocumentVersion version) {
        StorageLocation location = location(version);
        var object = storage.resolve(location).download(location, version.getRenditionObjectKey());
        return new DownloadedDocument(object.content(), object.sizeBytes(), MediaType.APPLICATION_PDF_VALUE,
                pdfName(version));
    }

    /** A version whose conversion failed isn't tried again for this long - its content never changes. */
    static final java.time.Duration RETRY_AFTER_FAILURE = java.time.Duration.ofHours(6);

    private DownloadedDocument convert(DocumentVersion version) {
        if ("failed".equals(version.getRenditionStatus()) && version.getUpdatedAt() != null
                && version.getUpdatedAt().isAfter(java.time.OffsetDateTime.now().minus(RETRY_AFTER_FAILURE))) {
            throw new FolderException(HttpStatus.UNPROCESSABLE_CONTENT, "PREVIEW_FAILED",
                    "This file couldn't be turned into a preview - download it to open it.");
        }
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
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // The converter is down or timed out - not the file's fault, so it is tried again next time.
            log.warn("Preview converter unavailable for version {}: {}", version.getVersionId(), e.getMessage());
            throw new FolderException(HttpStatus.SERVICE_UNAVAILABLE, "PREVIEW_UNAVAILABLE",
                    "Previews are unavailable just now - try again shortly, or download the file.");
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
