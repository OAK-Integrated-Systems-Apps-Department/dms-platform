package com.oak.dms.document;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.common.exception.ApiException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.document.event.DocumentVersionStoredEvent;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.folder.FolderException;
import com.oak.dms.preview.PreviewPolicyService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Document previews and renditions (DPRM): what a version is shown as in the browser, made on the server, kept, and
 * served only to people who may view the document.
 *
 * <h2>What is made</h2>
 * <ul>
 *   <li>The <b>preview PDF</b> of an Office file (Word, PowerPoint, OpenDocument, RTF, Visio - and, for the protected
 *       view, also .docx/.xls/.xlsx/.ods/.txt/.csv): converted by LibreOffice in the Gotenberg container
 *       ({@code dms.preview.converter-url}), kept beside the original encrypted the same way (V64).</li>
 *   <li>The <b>thumbnail</b>: a small JPEG of the first page of any PDF, image or convertible file, kept the same way
 *       (V135).</li>
 *   <li><b>Pages as pictures</b> ({@link #pages}, {@link #page}): drawn on request from the PDF, with the viewer's
 *       name burned in when the organisation's {@link PreviewPolicyService preview policy} asks for a watermark.
 *       This is the protected view - the file itself never reaches the browser.</li>
 * </ul>
 * Renditions belong to one version, so a new version never shows an old one's picture (DPRM-FR-003); they are made
 * when the version is stored ({@link RenditionGenerationListener}) and again on demand when missing; they are purged
 * with the version's file.
 *
 * <h2>What can go wrong</h2>
 * A file past {@code dms.preview.max-bytes} gets a partial preview - its first {@code dms.preview.partial-pages}
 * pages; past {@code dms.preview.partial-max-bytes} it is flagged for an administrator's manual review. A file that
 * can't be converted or drawn is recorded (V135) and listed for administrators ({@link #failures}); everywhere a
 * preview or thumbnail is shown, there is a fallback that leaves the document's details visible (DPRM-FR-006).
 * <p>
 * Every request checks the caller's access to the document again, so revoking a permission blocks previews at once
 * even though the renditions are still stored (alternate flow). Viewing is audited as DOCUMENT_VIEWED, once per
 * opening - not per page.
 *
 * <h2>FOLLOW-UP (known gaps, deliberately not fixed here)</h2>
 * <ul>
 *   <li><b>No backfill of thumbnails</b> for versions stored before V135: each is made the first time its thumbnail
 *       is asked for (or its preview opened). A background sweep over {@code thumbnail_status = 'pending'} would fill
 *       them in ahead of time.</li>
 *   <li><b>The preview policy covers the preview channel only.</b> Downloading from the file list is governed by the
 *       document's own permission, as before; {@code channel=preview} is what the preview's Download button sends.
 *       Previews of raw bytes the frontend fetches itself (a workflow's pinned supporting documents,
 *       {@code contentSource}) don't go through the policy.</li>
 *   <li><b>"Copy: allow" can't be offered inside the protected view</b> - the pages are pictures, so when print or
 *       download is restricted there is no selectable text even though copying is allowed. A text layer served
 *       only to people allowed to copy would need its own endpoint.</li>
 *   <li><b>Partial previews of an Office file</b> know how many pages they show but not how long the whole file is
 *       ({@code rendition_total_pages} stays null): LibreOffice is asked for pages 1..N and says nothing about the
 *       rest. A PDF knows its length.</li>
 * </ul>
 * A rendition is only ever a view: nothing here writes to the stored original, so no watermark or digital signature
 * on it can be altered.
 */
@Slf4j
@Service
public class DocumentRenditionService {

    /** Extensions converted here for the Office-style preview - everything else the browser renders itself, or can't be previewed. */
    static final Set<String> CONVERTED = Set.of("doc", "ppt", "pptx", "odt", "odp", "odg", "rtf", "vsd", "vsdx");
    /** Of those, the presentations - shown slide by slide. */
    static final Set<String> SLIDES = Set.of("ppt", "pptx", "odp");
    /** The browser can show these itself, but the protected view and the thumbnails need them as a PDF. */
    static final Set<String> ALSO_CONVERTIBLE = Set.of("docx", "xls", "xlsx", "ods", "txt", "csv");
    /** Pictures the server can read (javax.imageio) - a one-page document each. */
    static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff");

    /** What the slide viewer needs first: which version, how many slides, and their shape (points). */
    public record SlideDeck(UUID versionId, int slides, float width, float height) {
    }

    /**
     * What the page viewer needs first. {@code partial}: only the first {@code pages} pages are shown, of
     * {@code totalPages} when that is known (null when it isn't). {@code watermarked}: the viewer's name is burned
     * into every page.
     */
    public record PageDeck(UUID versionId, int pages, float width, float height, boolean partial, Integer totalPages,
                           boolean watermarked) {
    }

    /** One row of the administrator's list of previews that could not be made. */
    public record FailureView(UUID versionId, UUID documentId, String title, String fileName, int versionNumber,
                              String reason, OffsetDateTime failedAt) {
    }

    /**
     * The converted PDFs of the last few files opened, so stepping through pages doesn't fetch and decrypt the PDF
     * again for every one. Only PDFs up to {@link #CACHED_PDF_MAX_BYTES}.
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

    private static final DateTimeFormatter STAMP_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentAccessService documentAccessService;
    private final VersionContent versionContent;
    private final StorageLocationRepository storageLocationRepository;
    private final ObjectStorageServiceFactory storage;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final PreviewPolicyService policies;
    private final ApplicationEventPublisher events;
    private final RestClient converter;
    private final long maxBytes;
    private final long partialMaxBytes;
    private final int partialPages;
    private final int downloadMaxPages;

    public DocumentRenditionService(DocumentRepository documentRepository, DocumentVersionRepository versionRepository,
                                    DocumentAccessService documentAccessService, VersionContent versionContent,
                                    StorageLocationRepository storageLocationRepository,
                                    ObjectStorageServiceFactory storage, AuditService auditService, JdbcTemplate jdbc,
                                    PreviewPolicyService policies, ApplicationEventPublisher events,
                                    RestClient.Builder restClients,
                                    @Value("${dms.preview.converter-url:}") String converterUrl,
                                    @Value("${dms.preview.timeout:PT90S}") Duration timeout,
                                    @Value("${dms.preview.max-bytes:52428800}") long maxBytes,
                                    @Value("${dms.preview.partial-max-bytes:157286400}") long partialMaxBytes,
                                    @Value("${dms.preview.partial-pages:20}") int partialPages,
                                    @Value("${dms.preview.download-max-pages:200}") int downloadMaxPages) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.documentAccessService = documentAccessService;
        this.versionContent = versionContent;
        this.storageLocationRepository = storageLocationRepository;
        this.storage = storage;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.policies = policies;
        this.events = events;
        this.maxBytes = maxBytes;
        this.partialMaxBytes = Math.max(partialMaxBytes, maxBytes);
        this.partialPages = Math.max(1, partialPages);
        this.downloadMaxPages = Math.max(1, downloadMaxPages);
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

    /** Files LibreOffice has to turn into a PDF before they can be drawn. */
    static boolean needsConversion(String filename) {
        String ext = extension(filename);
        return CONVERTED.contains(ext) || ALSO_CONVERTIBLE.contains(ext);
    }

    static boolean isPdf(String filename) {
        return "pdf".equals(extension(filename));
    }

    static boolean isImage(String filename) {
        return IMAGES.contains(extension(filename));
    }

    /** Files the protected page view and the thumbnails can draw: PDFs, pictures, and what converts to a PDF. */
    static boolean protectable(String filename) {
        return isPdf(filename) || isImage(filename) || needsConversion(filename);
    }

    // ------------------------------------------------------------------
    // The Office-file PDF preview and the slide show (unchanged behaviour)
    // ------------------------------------------------------------------

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
        DownloadedDocument pdf = pdfOf(viewed.version(), viewed.document().getTenantId());
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
        SlidePages.Deck deck = SlidePages.describe(pdfBytes(viewed.version(), viewed.document().getTenantId(), true));
        audit(viewed, "slides");
        return new SlideDeck(viewed.version().getVersionId(), deck.slides(), deck.width(), deck.height());
    }

    /**
     * Slide {@code number} (from 1) as a JPEG about {@code width} pixels wide. Access is checked
     * for every slide; the view itself was audited when the deck was opened ({@link #slides}). The viewer's
     * name is burned in when the organisation's preview policy asks for a watermark.
     */
    public byte[] slide(UUID documentId, UUID requestedVersion, int number, int width) {
        Viewed viewed = viewable(documentId, requestedVersion);
        requireSlides(viewed.version());
        try {
            return SlidePages.render(pdfBytes(viewed.version(), viewed.document().getTenantId(), true), number - 1,
                    width, watermarkText(policies.mine()));
        } catch (IndexOutOfBoundsException e) {
            throw new ResourceNotFoundException("Slide", number);
        }
    }

    // ------------------------------------------------------------------
    // The protected view: pages as pictures, watermark burned in (DPRM-FR-005)
    // ------------------------------------------------------------------

    /**
     * The file as pages drawn on the server: how many, their shape, and whether it is a partial preview. Audited
     * as DOCUMENT_VIEWED once - here, not for every page.
     *
     * @param requestedVersion one of its versions; null = the current one
     */
    public PageDeck pages(UUID documentId, UUID requestedVersion) {
        Viewed viewed = locate(documentId, requestedVersion);
        requireProtectable(viewed.version());
        PreviewPolicyService.Policy policy = policies.mine();
        PageSource source = source(viewed.version(), viewed.document().getTenantId(), true);
        audit(viewed, "pages");
        return new PageDeck(viewed.version().getVersionId(), source.shown(), source.deck().width(),
                source.deck().height(), source.partial(), source.totalPages(), policy.watermarked());
    }

    /**
     * Page {@code number} (from 1) as a JPEG about {@code width} pixels wide. Access and the preview policy are
     * decided here for every page; the watermark is never something the caller asks for.
     */
    public byte[] page(UUID documentId, UUID requestedVersion, int number, int width) {
        Viewed viewed = locate(documentId, requestedVersion);
        requireProtectable(viewed.version());
        String watermark = watermarkText(policies.mine());
        PageSource source = source(viewed.version(), viewed.document().getTenantId(), true);
        if (number < 1 || number > source.shown()) {
            throw new ResourceNotFoundException("Page", number);
        }
        try {
            return source.image()
                    ? SlidePages.renderImage(source.bytes(), width, watermark)
                    : SlidePages.render(source.bytes(), number - 1, width, watermark);
        } catch (IndexOutOfBoundsException e) {
            throw new ResourceNotFoundException("Page", number);
        } catch (RuntimeException e) {
            fail(viewed.version(), viewed.document().getTenantId(), "The file could not be drawn: " + reasonOf(e));
            throw new FolderException(HttpStatus.UNPROCESSABLE_CONTENT, "PREVIEW_FAILED",
                    "This file couldn't be turned into a preview - download it to open it.");
        }
    }

    /**
     * The Download button of a preview, under the organisation's preview policy: {@code allow} = empty (the
     * ordinary download runs), {@code block} = refused, {@code watermark} = a PDF of the pages with the viewer's
     * name burned in, which has no text to copy. A file that can't be watermarked is refused, never sent plain.
     */
    public Optional<DownloadedDocument> downloadFromPreview(UUID documentId, UUID requestedVersion) {
        PreviewPolicyService.Policy policy = policies.mine();
        if (PreviewPolicyService.ALLOW.equals(policy.download())) {
            return Optional.empty();
        }
        if (PreviewPolicyService.BLOCK.equals(policy.download())) {
            throw new FolderException(HttpStatus.FORBIDDEN, "PREVIEW_DOWNLOAD_BLOCKED",
                    "Your organisation doesn't allow downloading from a preview.");
        }
        Viewed viewed = locate(documentId, requestedVersion);
        DocumentVersion version = viewed.version();
        if (!protectable(version.getOriginalFilename())) {
            throw new FolderException(HttpStatus.FORBIDDEN, "PREVIEW_DOWNLOAD_BLOCKED",
                    "This kind of file can't carry a watermark, so it can't be downloaded from a preview.");
        }
        PageSource source = source(version, viewed.document().getTenantId(), false);
        if (source.partial() || source.shown() > downloadMaxPages) {
            throw new FolderException(HttpStatus.CONTENT_TOO_LARGE, "PREVIEW_TOO_LARGE",
                    "This file is too long to download with a watermark from a preview.");
        }
        String watermark = watermarkText(policy);
        byte[] pdf = source.image()
                ? SlidePages.imagePdf(source.bytes(), watermark)
                : SlidePages.flattenedPdf(source.bytes(), watermark, downloadMaxPages);
        auditService.event(AuditActionCatalog.DOCUMENT_DOWNLOADED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(viewed.document().getDocumentId())
                .resourceName(viewed.document().getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("as", "watermarked copy from preview", "versionId", version.getVersionId()))
                .log();
        return Optional.of(new DownloadedDocument(new ByteArrayInputStream(pdf), pdf.length,
                MediaType.APPLICATION_PDF_VALUE, baseName(version) + " (watermarked).pdf"));
    }

    // ------------------------------------------------------------------
    // Thumbnails (DPRM-FR-001, DPRM-FR-002)
    // ------------------------------------------------------------------

    /**
     * The first-page thumbnail (a small JPEG) of a version - from storage when it was made already, otherwise made
     * now and kept. Empty when the file has none (a type that can't be drawn, or one that failed): the caller shows
     * a generic icon, and the document's details stay visible either way (DPRM-FR-006).
     *
     * @param requestedVersion one of its versions; null = the current one
     */
    public Optional<byte[]> thumbnail(UUID documentId, UUID requestedVersion) {
        Viewed viewed = locate(documentId, requestedVersion);
        if (!protectable(viewed.version().getOriginalFilename())) {
            return Optional.empty();
        }
        return Optional.ofNullable(thumbnailOf(viewed.version(), viewed.document().getTenantId()));
    }

    /** The id of the version a thumbnail request is for - used as its cache tag. */
    public UUID versionToShow(UUID documentId, UUID requestedVersion) {
        return locate(documentId, requestedVersion).version().getVersionId();
    }

    private byte[] thumbnailOf(DocumentVersion version, UUID tenantId) {
        if ("completed".equals(version.getThumbnailStatus()) && version.getThumbnailObjectKey() != null) {
            try {
                return readAll(stored(version, version.getThumbnailObjectKey(), "image/jpeg", "thumbnail.jpg").content());
            } catch (StorageObjectNotFoundException gone) {
                log.info("Thumbnail of version {} is missing from storage - making it again", version.getVersionId());
            }
        }
        if ("unsupported".equals(version.getThumbnailStatus())) {
            return null;
        }
        if ("failed".equals(version.getThumbnailStatus()) && recentlyFailed(version)) {
            return null;
        }
        byte[] jpeg;
        try {
            jpeg = isImage(version.getOriginalFilename())
                    ? SlidePages.renderImage(readAll(versionContent.open(version, tenantId).content()),
                    SlidePages.THUMBNAIL_WIDTH, null)
                    : SlidePages.thumbnail(pdfBytes(version, tenantId, false));
        } catch (StorageObjectNotFoundException e) {
            // The file isn't in storage (yet, or any more): that is not a file that can't be drawn, so nothing is
            // recorded against the version - it is simply tried again the next time it is asked for.
            return null;
        } catch (ApiException e) {
            // Not servable, no converter, too large, already recorded as failed: nothing to draw, nothing new to record.
            return null;
        } catch (RuntimeException e) {
            log.warn("Could not draw a thumbnail for version {}: {}", version.getVersionId(), e.getMessage());
            jdbc.update("update document_versions set thumbnail_status = 'failed', updated_at = now() where version_id = ?",
                    version.getVersionId());
            fail(version, tenantId, "The first page could not be drawn: " + reasonOf(e));
            return null;
        }
        StorageLocation location = location(version);
        String key = "renditions/" + version.getTenantId() + "/" + version.getDocumentId() + "/"
                + version.getVersionId() + ".thumb.jpg";
        storage.resolve(location).upload(location, key, new ByteArrayInputStream(jpeg), jpeg.length, "image/jpeg");
        // A thumbnail that can be drawn means the file itself is fine - a failure recorded earlier is cleared.
        jdbc.update("update document_versions set thumbnail_object_key = ?, thumbnail_status = 'completed', "
                + "rendition_failure_reason = null, rendition_failed_at = null, updated_at = now() "
                + "where version_id = ?", key, version.getVersionId());
        return jpeg;
    }

    /**
     * Makes a version's renditions now, in the background, with nobody asking: the thumbnail, and for a file that has
     * to be converted, its preview PDF as well (DPRM-FR-001/003). Never throws - a failure is recorded on the
     * version for the administrator, and the rendition is made again on demand.
     */
    public void generate(UUID tenantId, UUID versionId) {
        try {
            DocumentVersion version = versionRepository.findById(versionId)
                    .filter(v -> tenantId.equals(v.getTenantId()) && !v.isDeleted() && v.getPurgedAt() == null)
                    .orElse(null);
            if (version == null || !protectable(version.getOriginalFilename())) {
                return;
            }
            if (needsConversion(version.getOriginalFilename())) {
                try {
                    pdfOf(version, tenantId);
                } catch (ApiException e) {
                    // The reason (no converter, file too large, conversion failed) is recorded where it happened.
                    log.debug("No preview PDF made for version {}: {}", versionId, e.getMessage());
                }
            }
            thumbnailOf(versionRepository.findById(versionId).orElse(version), tenantId);
        } catch (RuntimeException e) {
            log.warn("Could not make the renditions of version {}: {}", versionId, e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Failures for administrator review (workflow step 6)
    // ------------------------------------------------------------------

    /** Versions of this organisation whose preview or thumbnail could not be made, newest failure first. */
    @Transactional(readOnly = true)
    public PageResponse<FailureView> failures(Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        Long total = jdbc.queryForObject("select count(*) from document_versions where tenant_id = ? "
                + "and rendition_failed_at is not null and not is_deleted and purged_at is null", Long.class, tenantId);
        List<FailureView> rows = jdbc.query("""
                        select v.version_id, v.document_id, d.title, v.original_filename, v.version_number,
                               v.rendition_failure_reason, v.rendition_failed_at
                          from document_versions v join documents d on d.document_id = v.document_id
                         where v.tenant_id = ? and v.rendition_failed_at is not null
                           and not v.is_deleted and v.purged_at is null
                         order by v.rendition_failed_at desc
                         limit ? offset ?""",
                (rs, n) -> new FailureView(rs.getObject("version_id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getString("title"), rs.getString("original_filename"), rs.getInt("version_number"),
                        rs.getString("rendition_failure_reason"),
                        rs.getObject("rendition_failed_at", OffsetDateTime.class)),
                tenantId, pageable.getPageSize(), pageable.getOffset());
        return PageResponse.from(new org.springframework.data.domain.PageImpl<>(rows, pageable, total == null ? 0 : total));
    }

    /** Clears a version's failure and makes its renditions again, in the background. */
    @Transactional
    public void retry(UUID versionId) {
        UUID tenantId = TenantContext.getTenantId();
        int changed = jdbc.update("""
                update document_versions set rendition_status = case when rendition_status = 'failed' then 'pending'
                       else rendition_status end, thumbnail_status = 'pending', rendition_failure_reason = null,
                       rendition_failed_at = null, updated_at = now()
                 where version_id = ? and tenant_id = ? and not is_deleted and purged_at is null""", versionId, tenantId);
        if (changed == 0) {
            throw new ResourceNotFoundException("DocumentVersion", versionId);
        }
        UUID documentId = jdbc.queryForObject("select document_id from document_versions where version_id = ?",
                UUID.class, versionId);
        events.publishEvent(new DocumentVersionStoredEvent(tenantId, documentId, versionId));
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    private record Viewed(Document document, DocumentVersion version) {
    }

    /** The document (which the caller must be able to view) and the version to show. */
    private Viewed locate(UUID documentId, UUID requestedVersion) {
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
        return new Viewed(document, version);
    }

    /** As {@link #locate}, for the Office-file PDF preview: only for files that are converted. */
    private Viewed viewable(UUID documentId, UUID requestedVersion) {
        Viewed viewed = locate(documentId, requestedVersion);
        if (!converted(viewed.version().getOriginalFilename())) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "PREVIEW_NOT_CONVERTED",
                    "This file is shown in the browser as it is - there's nothing to convert.");
        }
        return viewed;
    }

    private static void requireSlides(DocumentVersion version) {
        if (!slides(version.getOriginalFilename())) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "PREVIEW_NOT_SLIDES",
                    "Only presentations are shown as slides.");
        }
    }

    private static void requireProtectable(DocumentVersion version) {
        if (!protectable(version.getOriginalFilename())) {
            throw new FolderException(HttpStatus.BAD_REQUEST, "PREVIEW_NOT_PAGES",
                    "This kind of file has no page preview - download it to open it.");
        }
    }

    /** A file ready to be drawn page by page: the PDF (or the picture), its shape, and how much of it is shown. */
    private record PageSource(byte[] bytes, boolean image, SlidePages.Deck deck, int shown, boolean partial,
                              Integer totalPages) {
    }

    private PageSource source(DocumentVersion version, UUID tenantId, boolean remember) {
        String name = version.getOriginalFilename();
        if (isImage(name)) {
            byte[] bytes = readAll(versionContent.open(version, tenantId).content());
            SlidePages.Deck deck = SlidePages.describeImage(bytes);
            if (deck == null) {
                fail(version, tenantId, "The file is not a readable picture.");
                throw new FolderException(HttpStatus.UNPROCESSABLE_CONTENT, "PREVIEW_FAILED",
                        "This file couldn't be turned into a preview - download it to open it.");
            }
            return new PageSource(bytes, true, deck, 1, false, 1);
        }
        boolean original = isPdf(name);
        if (original && version.getSizeBytes() > partialMaxBytes) {
            fail(version, tenantId, "The file is " + megabytes(version.getSizeBytes()) + " MB, over the "
                    + megabytes(partialMaxBytes) + " MB preview limit - needs manual review.");
            throw new FolderException(HttpStatus.CONTENT_TOO_LARGE, "PREVIEW_TOO_LARGE",
                    "This file is too large to preview - download it to open it.");
        }
        byte[] pdf = pdfBytes(version, tenantId, remember);
        SlidePages.Deck deck;
        try {
            deck = SlidePages.describe(pdf);
        } catch (RuntimeException e) {
            fail(version, tenantId, "The PDF could not be read: " + reasonOf(e));
            throw new FolderException(HttpStatus.UNPROCESSABLE_CONTENT, "PREVIEW_FAILED",
                    "This file couldn't be turned into a preview - download it to open it.");
        }
        if (deck.slides() == 0) {
            fail(version, tenantId, "The file has no pages.");
            throw new FolderException(HttpStatus.UNPROCESSABLE_CONTENT, "PREVIEW_FAILED",
                    "This file couldn't be turned into a preview - download it to open it.");
        }
        if (original) {
            boolean partial = version.getSizeBytes() > maxBytes;
            int shown = partial ? Math.min(deck.slides(), partialPages) : deck.slides();
            return new PageSource(pdf, false, deck, shown, partial && shown < deck.slides(), deck.slides());
        }
        // A converted file: the stored PDF is already a cut when the original was past the size limit.
        return new PageSource(pdf, false, deck, deck.slides(), version.isRenditionPartial(),
                version.isRenditionPartial() ? version.getRenditionTotalPages() : Integer.valueOf(deck.slides()));
    }

    /** Burned into every page when the policy says "watermark": who looked, and when. */
    private static String watermarkText(PreviewPolicyService.Policy policy) {
        if (!policy.watermarked()) {
            return null;
        }
        String who = TenantContext.getDisplayNameOrNull();
        return (who == null || who.isBlank() ? "Confidential" : who) + " · "
                + STAMP_TIME.format(OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** The version's PDF in memory - the original when it is one, otherwise the stored or converted preview. */
    private byte[] pdfBytes(DocumentVersion version, UUID tenantId, boolean remember) {
        byte[] cached = recentPdfs.get(version.getVersionId());
        if (cached != null) {
            return cached;
        }
        byte[] pdf = isPdf(version.getOriginalFilename())
                ? readAll(versionContent.open(version, tenantId).content())
                : readAll(pdfOf(version, tenantId).content());
        if (remember && pdf.length <= CACHED_PDF_MAX_BYTES) {
            recentPdfs.put(version.getVersionId(), pdf);
        }
        return pdf;
    }

    private DownloadedDocument pdfOf(DocumentVersion version, UUID tenantId) {
        if (version.getRenditionObjectKey() != null && "completed".equals(version.getRenditionStatus())) {
            try {
                return stored(version, version.getRenditionObjectKey(), MediaType.APPLICATION_PDF_VALUE, pdfName(version));
            } catch (StorageObjectNotFoundException gone) {
                // The cache entry is missing (alternate flow) - made again before serving.
                log.info("Preview PDF of version {} is missing from storage - converting it again", version.getVersionId());
            }
        }
        return convert(version, tenantId);
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

    private DownloadedDocument stored(DocumentVersion version, String key, String mime, String name) {
        StorageLocation location = location(version);
        var object = storage.resolve(location).download(location, key);
        return new DownloadedDocument(object.content(), object.sizeBytes(), mime, name);
    }

    /** A version whose conversion failed isn't tried again for this long - its content never changes. */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofHours(6);

    private static boolean recentlyFailed(DocumentVersion version) {
        OffsetDateTime when = version.getRenditionFailedAt() != null ? version.getRenditionFailedAt()
                : version.getUpdatedAt();
        return when != null && when.isAfter(OffsetDateTime.now().minus(RETRY_AFTER_FAILURE));
    }

    private DownloadedDocument convert(DocumentVersion version, UUID tenantId) {
        if ("failed".equals(version.getRenditionStatus()) && recentlyFailed(version)) {
            throw new FolderException(HttpStatus.UNPROCESSABLE_CONTENT, "PREVIEW_FAILED",
                    "This file couldn't be turned into a preview - download it to open it.");
        }
        if (converter == null) {
            throw new FolderException(HttpStatus.SERVICE_UNAVAILABLE, "PREVIEW_UNAVAILABLE",
                    "Previews of this file type aren't set up on this server - download it to open it.");
        }
        if (version.getSizeBytes() > partialMaxBytes) {
            failConversion(version, tenantId, "The file is " + megabytes(version.getSizeBytes()) + " MB, over the "
                    + megabytes(partialMaxBytes) + " MB preview limit - needs manual review.");
            throw new FolderException(HttpStatus.CONTENT_TOO_LARGE, "PREVIEW_TOO_LARGE",
                    "This file is too large to preview - download it to open it.");
        }
        // Past the normal limit the file is converted for its first pages only (partial preview).
        boolean partial = version.getSizeBytes() > maxBytes;
        byte[] original = readAll(versionContent.open(version, tenantId).content());

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
            if (partial) {
                form.add("nativePageRanges", "1-" + partialPages);
            }
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
            failConversion(version, tenantId, "The file could not be converted: " + reasonOf(e));
            throw new FolderException(HttpStatus.UNPROCESSABLE_CONTENT, "PREVIEW_FAILED",
                    "This file couldn't be turned into a preview - download it to open it.");
        }
        if (pdf == null || pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F') {
            failConversion(version, tenantId, "The converter did not return a PDF.");
            throw new FolderException(HttpStatus.UNPROCESSABLE_CONTENT, "PREVIEW_FAILED",
                    "This file couldn't be turned into a preview - download it to open it.");
        }

        // Kept beside the original, encrypted like it, so the next preview is instant.
        StorageLocation location = location(version);
        String key = "renditions/" + version.getTenantId() + "/" + version.getDocumentId() + "/"
                + version.getVersionId() + ".pdf";
        storage.resolve(location).upload(location, key, new ByteArrayInputStream(pdf), pdf.length,
                MediaType.APPLICATION_PDF_VALUE);
        jdbc.update("update document_versions set rendition_status = 'completed', rendition_object_key = ?, "
                + "rendition_partial = ?, rendition_total_pages = null, rendition_failure_reason = null, "
                + "rendition_failed_at = null, updated_at = now() where version_id = ?", key, partial,
                version.getVersionId());
        return new DownloadedDocument(new ByteArrayInputStream(pdf), pdf.length, MediaType.APPLICATION_PDF_VALUE,
                pdfName(version));
    }

    /**
     * Records why a version has no preview or thumbnail, for the administrator's list - and audits it. Never throws.
     * Leaves the rendition itself alone: a picture that couldn't be drawn doesn't take a good stored PDF with it.
     */
    private void fail(DocumentVersion version, UUID tenantId, String reason) {
        record(version, tenantId, reason, false);
    }

    /** As {@link #fail}, for a conversion that failed: the version's preview PDF is marked failed too (retried after a while). */
    private void failConversion(DocumentVersion version, UUID tenantId, String reason) {
        record(version, tenantId, reason, true);
    }

    private void record(DocumentVersion version, UUID tenantId, String reason, boolean conversion) {
        try {
            String shortReason = reason.length() > 480 ? reason.substring(0, 480) : reason;
            jdbc.update("update document_versions set rendition_status = case when ? then 'failed' "
                    + "else rendition_status end, rendition_object_key = case when ? then null "
                    + "else rendition_object_key end, rendition_failure_reason = ?, rendition_failed_at = now(), "
                    + "updated_at = now() where version_id = ?", conversion, conversion, shortReason,
                    version.getVersionId());
            log.warn("Preview of version {} ({}) failed: {}", version.getVersionId(), version.getOriginalFilename(),
                    shortReason);
            auditService.event(AuditActionCatalog.PREVIEW_RENDITION_FAILED)
                    .tenantId(tenantId)
                    .objectType(AuditObjectTypes.DOCUMENT)
                    .objectId(version.getDocumentId())
                    .resourceName(version.getOriginalFilename())
                    .outcome(AuditOutcome.FAILURE)
                    .newValue(Map.of("versionId", version.getVersionId(), "reason", shortReason))
                    .log();
        } catch (RuntimeException e) {
            log.warn("Could not record the preview failure of version {}: {}", version.getVersionId(), e.getMessage());
        }
    }

    private StorageLocation location(DocumentVersion version) {
        return storageLocationRepository.findByStorageLocationIdAndDeletedFalse(version.getStorageLocationId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", version.getVersionId()));
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String reasonOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static long megabytes(long bytes) {
        return Math.round(bytes / (1024.0 * 1024.0));
    }

    private static String baseName(DocumentVersion version) {
        String name = version.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String pdfName(DocumentVersion version) {
        return baseName(version) + ".pdf";
    }

    static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
