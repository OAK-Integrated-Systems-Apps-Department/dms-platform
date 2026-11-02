package com.oak.dms.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.relationship.dto.RelationshipFilter;
import com.oak.dms.relationship.service.RelationshipBrowseFilter;
import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.common.exception.MetadataValidationFailedException;
import com.oak.dms.document.dto.DocumentListItem;
import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DocumentReviewResponse;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.document.dto.OcrRerunRequest;
import com.oak.dms.document.dto.OcrRerunResponse;
import com.oak.dms.document.dto.OcrStatusResponse;
import com.oak.dms.document.dto.ReviewQueueItemResponse;
import com.oak.dms.document.dto.RubberBandRequest;
import com.oak.dms.document.dto.RubberBandResponse;
import com.oak.dms.document.dto.SaveReviewRequest;
import com.oak.dms.document.dto.SaveReviewResponse;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.dto.FolderDtos.DocumentAccessResponse;
import com.oak.dms.folder.dto.FolderDtos.UpdateFolderAccessRequest;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.ocrworker.OcrWorkerClient;
import com.oak.dms.scanning.controller.IngestionTicketResponses;
import com.oak.dms.scanning.dto.IngestionTicketResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code Controller -> DocumentService -> ObjectStorageService -> adapter ->
 * provider} - this controller never touches a storage vendor SDK or
 * {@code java.nio.file} itself; every byte crosses through
 * {@link DocumentService}.
 *
 * <h2>OCR authorization (merge note)</h2>
 * The OCR branch's routes carried no {@code @PreAuthorize} at all - its own
 * comment said "any authenticated user may upload/OCR within their own tenant
 * ... there is no finer-grained document permission model yet". There is one
 * now, so every OCR route here is gated twice:
 * <ul>
 *   <li>an {@code os.*} capability from the OCR &amp; Scanning module, which
 *       V10 has seeded since long before either branch; and</li>
 *   <li>document-level access - the queue and saved reviews list only what the
 *       caller can see, reading and page images need {@code view}, and saving a
 *       review needs {@code edit}. Enforced in the service, against the same
 *       {@code DocumentAccessView} the browser uses, so there is one answer to
 *       "can this person see this document" rather than two that can drift.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Document upload, download, lifecycle, OCR and review")
public class DocumentController {

    /** Shorthand for the permission-code class, which is otherwise named in full on every route. */
    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";

    private final DocumentService documentService;
    private final DocumentAccessService documentAccessService;
    private final DocumentVersionService versionService;
    private final DocumentRenditionService renditionService;
    private final DuplicateFinder duplicateFinder;
    private final DocumentMetadataService documentMetadataService;
    private final ObjectMapper objectMapper;
    private final RelationshipBrowseFilter relationshipBrowseFilter;

    /**
     * Returns an ingestion ticket, uniformly across scan providers - the
     * document only exists once the file has passed the malware scan. See
     * {@link IngestionTicketResponses} for the status-code mapping
     * (201 clean / 202 pending / 422 rejected). A disallowed type (415) or an
     * oversized file (413) is refused before anything is stored.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)")
    @Operation(summary = "Upload a new document - scanned for malware before it is created")
    public ResponseEntity<IngestionTicketResponse> uploadDocument(@RequestPart("file") MultipartFile file,
                                                                   @RequestParam("documentTypeId") UUID documentTypeId,
                                                                   @RequestParam("title") String title,
                                                                   @RequestParam(value = "folderId", required = false) UUID folderId,
                                                                   @RequestParam(value = "refuseDuplicates", defaultValue = "false")
                                                                   boolean refuseDuplicates,
                                                                   @RequestParam(value = "requestedFields", required = false)
                                                                   String requestedFieldsJson,
                                                                   @RequestParam(value = "duplicateOfDocumentId", required = false)
                                                                   UUID duplicateOfDocumentId)
            throws IOException {
        // requestedFields is the Scan Documents flow's field picker. Absent for
        // an ordinary upload, which is why it stays optional rather than
        // becoming a second endpoint: it is the same upload with one more thing
        // asked of it, and splitting it would mean two paths into the scan gate
        // to keep in step.
        List<String> requestedFields = parseRequestedFields(requestedFieldsJson);
        try (InputStream content = file.getInputStream()) {
            IngestionTicketResponse ticket = documentService.uploadDocument(documentTypeId, title,
                    file.getOriginalFilename(), file.getSize(), content, folderId, refuseDuplicates, requestedFields,
                    duplicateOfDocumentId);
            return IngestionTicketResponses.toResponseEntity(ticket);
        }
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "Browse documents",
            description = "folderId: one folder's documents; without it, documents not in any folder - or every "
                    + "document with all=true. q searches titles. documentTypeId narrows to one type and "
                    + "excludeDocumentId leaves one document out (the document picker). relationshipType (a type code) and "
                    + "relatedTo (a document id) keep documents linked that way - over every folder; direction (source|target|any) "
                    + "says which side of the link the returned documents are on: relatedTo=X&relationshipType=SUPERSEDES&direction=source "
                    + "is the documents that supersede X. ownerId and ownerDepartmentId keep documents owned by that user and/or "
                    + "department (access filtering still applies). Newest first by default.")
    public PageResponse<DocumentListItem> listDocuments(
            @RequestParam(value = "folderId", required = false) UUID folderId,
            @RequestParam(value = "all", defaultValue = "false") boolean all,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "documentTypeId", required = false) UUID documentTypeId,
            @RequestParam(value = "excludeDocumentId", required = false) UUID excludeDocumentId,
            @RequestParam(value = "relationshipType", required = false) String relationshipType,
            @RequestParam(value = "relatedTo", required = false) UUID relatedTo,
            @RequestParam(value = "direction", required = false) String direction,
            @RequestParam(value = "ownerId", required = false) UUID ownerId,
            @RequestParam(value = "ownerDepartmentId", required = false) UUID ownerDepartmentId,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        OwnerFilter owner = new OwnerFilter(ownerId, ownerDepartmentId);
        RelationshipFilter relationship = relationshipBrowseFilter.resolve(relationshipType, relatedTo, direction);
        if (relationship != null) {
            return PageResponse.from(documentService.listRelatedDocuments(query, documentTypeId, excludeDocumentId,
                    relationship, owner, pageable));
        }
        return PageResponse.from(documentService.listDocuments(folderId, all, query, documentTypeId, excludeDocumentId,
                owner, pageable));
    }

    @GetMapping("/shared-with-me")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "Documents shared with the caller - restricted documents whose own list names them or one "
            + "of their roles, not ones they own. Newest first by default.")
    public PageResponse<DocumentListItem> sharedWithMe(
            @RequestParam(value = "q", required = false) String query,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(documentService.listSharedWithMe(query, pageable));
    }

    /**
     * @param name   the file's name, as it will be uploaded
     * @param sha256 its SHA-256 in hex, worked out by the browser; null to check the name only
     */
    public record DuplicateCheckFile(@jakarta.validation.constraints.NotBlank @Size(max = 500) String name,
                                     @jakarta.validation.constraints.Pattern(regexp = "^[0-9a-fA-F]{64}$")
                                     String sha256) {
    }

    /** @param folderId the folder the files are going into; null = the top level */
    public record DuplicateCheckRequest(UUID folderId, @NotEmpty @Size(max = 500) List<@Valid DuplicateCheckFile> files) {
    }

    public record DuplicateCheckResult(String name, List<DuplicateFinder.Match> matches) {
    }

    /**
     * Before uploading: documents whose text is nearly the same as this file's (a re-saved copy, a light
     * edit, the same report renamed). The file is read in memory only - nothing is stored - and must be
     * at most 25 MB; files with no readable text (images, email) find nothing.
     */
    @PostMapping(value = "/duplicates/similar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)")
    @Operation(summary = "Before uploading: documents with nearly the same text as this file")
    public List<DuplicateFinder.Match> similarDocuments(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.getSize() > DocumentFingerprints.MAX_BYTES
                || !DocumentFingerprints.readable(file.getOriginalFilename())) {
            return List.of();
        }
        return DocumentFingerprints.of(file.getOriginalFilename(), file.getBytes())
                .map(duplicateFinder::findSimilar)
                .orElse(List.of());
    }

    @PostMapping("/duplicates")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)")
    @Operation(summary = "Before uploading: which of these files are already in the DMS",
            description = "Same content (SHA-256) in any document the caller can open, or the same file name in the "
                    + "target folder. Documents hidden from the caller are never reported.")
    public List<DuplicateCheckResult> checkDuplicates(@Valid @RequestBody DuplicateCheckRequest request) {
        return request.files().stream()
                .map(f -> new DuplicateCheckResult(f.name(), duplicateFinder.find(f.sha256(), f.name(),
                        request.folderId())))
                .toList();
    }

    /** @param folderId the destination folder; null = take the documents out of any folder */
    public record MoveDocumentsRequest(@NotEmpty @Size(max = 500) List<UUID> documentIds, UUID folderId) {
    }

    @PostMapping("/move")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_MOVE)")
    @Operation(summary = "Move documents into a folder (folderId null = out of any folder)")
    public Map<String, Integer> moveDocuments(@Valid @RequestBody MoveDocumentsRequest request) {
        return Map.of("moved", documentService.moveDocuments(request.documentIds(), request.folderId()));
    }
    
    @GetMapping("/{id}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "Get a document's metadata")
    public DocumentResponse getDocument(@PathVariable("id") UUID id) {
        return documentService.getDocument(id);
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "Download a document's current (or latest) version; inline=true to show it in the browser (preview)")
    public ResponseEntity<StreamingResponseBody> downloadDocument(
            @PathVariable("id") UUID id,
            @RequestParam(value = "inline", defaultValue = "false") boolean inline) {
        DownloadedDocument downloaded = inline ? documentService.previewDocument(id) : documentService.downloadDocument(id);
        StreamingResponseBody body = outputStream -> {
            try (InputStream content = downloaded.content()) {
                content.transferTo(outputStream);
            }
        };

        String contentType = downloaded.contentType() != null && !downloaded.contentType().isBlank()
                ? downloaded.contentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(downloaded.sizeBytes()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (inline ? "inline" : "attachment") + "; filename=\""
                                + sanitizeForHeader(downloaded.filename()) + "\"")
                // A preview is shown in the page - never let the browser guess a riskier type.
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_DELETE)")
    @Operation(summary = "Soft-delete a document")
    public ResponseEntity<Void> deleteDocument(@PathVariable("id") UUID id) {
        documentService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }

    /** Office files the browser can't show - Word .doc, PowerPoint, OpenDocument, RTF, Visio - as a PDF. */
    @GetMapping("/{id}/preview.pdf")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "Preview an Office document as a PDF - converted on first use, then kept")
    public ResponseEntity<StreamingResponseBody> previewPdf(@PathVariable("id") UUID id) {
        DownloadedDocument pdf = renditionService.preview(id);
        StreamingResponseBody body = outputStream -> {
            try (InputStream content = pdf.content()) {
                content.transferTo(outputStream);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdf.sizeBytes()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + sanitizeForHeader(pdf.filename()) + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /** A presentation (.ppt, .pptx, .odp) shown slide by slide: how many slides, and their shape. */
    @GetMapping("/{id}/preview/slides")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "Open a presentation as slides - converted on first use; audited as viewed")
    public DocumentRenditionService.SlideDeck slides(@PathVariable("id") UUID id) {
        return renditionService.slides(id, null);
    }

    @GetMapping("/{id}/preview/slides/{number}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "One slide of a presentation as a JPEG about `width` pixels wide")
    public ResponseEntity<byte[]> slide(@PathVariable("id") UUID id, @PathVariable("number") int number,
                                        @RequestParam(name = "width", defaultValue = "1280") int width) {
        return slideImage(renditionService.slide(id, null, number, width));
    }

    @GetMapping("/{id}/versions/{versionId}/preview/slides")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DVC_VERSIONS_VIEW)")
    @Operation(summary = "Open an older version of a presentation as slides")
    public DocumentRenditionService.SlideDeck versionSlides(@PathVariable("id") UUID id,
                                                            @PathVariable("versionId") UUID versionId) {
        return renditionService.slides(id, versionId);
    }

    @GetMapping("/{id}/versions/{versionId}/preview/slides/{number}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DVC_VERSIONS_VIEW)")
    @Operation(summary = "One slide of an older version of a presentation as a JPEG")
    public ResponseEntity<byte[]> versionSlide(@PathVariable("id") UUID id, @PathVariable("versionId") UUID versionId,
                                               @PathVariable("number") int number,
                                               @RequestParam(name = "width", defaultValue = "1280") int width) {
        return slideImage(renditionService.slide(id, versionId, number, width));
    }

    private static ResponseEntity<byte[]> slideImage(byte[] jpeg) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(jpeg);
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DVC_VERSIONS_VIEW)")
    @Operation(summary = "A document's version history, newest first")
    public List<DocumentVersionService.VersionItem> versions(@PathVariable("id") UUID id) {
        return versionService.list(id);
    }

    @GetMapping("/{id}/versions/{versionId}/preview.pdf")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DVC_VERSIONS_VIEW)")
    @Operation(summary = "Preview an older version of an Office document as a PDF - converted on first use")
    public ResponseEntity<StreamingResponseBody> previewVersionPdf(@PathVariable("id") UUID id,
                                                                   @PathVariable("versionId") UUID versionId) {
        DownloadedDocument pdf = renditionService.preview(id, versionId);
        StreamingResponseBody body = outputStream -> {
            try (InputStream content = pdf.content()) {
                content.transferTo(outputStream);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdf.sizeBytes()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + sanitizeForHeader(pdf.filename()) + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    @GetMapping("/{id}/versions/{versionId}/content")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DVC_VERSIONS_VIEW)")
    @Operation(summary = "Download one version of a document; inline=true to show it in the browser (preview)")
    public ResponseEntity<StreamingResponseBody> downloadVersion(
            @PathVariable("id") UUID id,
            @PathVariable("versionId") UUID versionId,
            @RequestParam(value = "inline", defaultValue = "false") boolean inline) {
        DownloadedDocument downloaded = inline ? versionService.preview(id, versionId)
                : versionService.download(id, versionId);
        StreamingResponseBody body = outputStream -> {
            try (InputStream content = downloaded.content()) {
                content.transferTo(outputStream);
            }
        };
        String contentType = downloaded.contentType() != null && !downloaded.contentType().isBlank()
                ? downloaded.contentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(downloaded.sizeBytes()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (inline ? "inline" : "attachment") + "; filename=\"" + sanitizeForHeader(downloaded.filename()) + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /** Same ticket and status codes as {@link #uploadDocument} - the version exists once the file is clean. */
    @PostMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)")
    @Operation(summary = "Upload a new version - scanned for malware, then it becomes the current version")
    public ResponseEntity<IngestionTicketResponse> uploadVersion(@PathVariable("id") UUID id,
                                                                 @RequestPart("file") MultipartFile file,
                                                                 @RequestParam(value = "comment", required = false)
                                                                 String comment) throws IOException {
        try (InputStream content = file.getInputStream()) {
            return IngestionTicketResponses.toResponseEntity(versionService.upload(id, file.getOriginalFilename(),
                    file.getSize(), content, comment));
        }
    }

    @PostMapping("/{id}/versions/{versionId}/restore")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DVC_VERSIONS_RESTORE)")
    @Operation(summary = "Make an older version current again - added as a new version with its content")
    public ResponseEntity<IngestionTicketResponse> restoreVersion(@PathVariable("id") UUID id,
                                                                  @PathVariable("versionId") UUID versionId) {
        return IngestionTicketResponses.toResponseEntity(versionService.restore(id, versionId));
    }

    @GetMapping("/{id}/access")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DSC_FOLDER_ACCESS_VIEW, "
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).DSC_FOLDER_ACCESS_ASSIGN)")
    @Operation(summary = "Who can open the document - its own list, or the folder setting it follows")
    public DocumentAccessResponse access(@PathVariable("id") UUID id) {
        return documentAccessService.getAccess(id);
    }

    @PutMapping("/{id}/access")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DSC_FOLDER_ACCESS_ASSIGN)")
    @Operation(summary = "Give the document its own list of users and roles (restricted), or make it follow its folder (inherit)")
    public DocumentAccessResponse updateAccess(@PathVariable("id") UUID id,
                                               @Valid @RequestBody UpdateFolderAccessRequest request) {
        return documentAccessService.updateAccess(id, request);
    }

    /** Strips characters that could break out of the quoted filename in a Content-Disposition header. */
    private static String sanitizeForHeader(String filename) {
        if (filename == null || filename.isBlank()) {
            return "download";
        }
        return filename.replaceAll("[\\r\\n\"]", "_");
    }

    // ------------------------------------------------------------------ OCR
    // Re-applied from the OCR branch. Bodies are unchanged; what is new on
    // every route is the @PreAuthorize, and the document-level access the
    // service now applies behind it.

    @Operation(summary = "Re-run OCR for specific fields (or, with no body, everything already requested) on a document version",
            description = "Runs asynchronously -- returns an acceptance ack, not the OCR result. Poll "
                    + "GET /{documentId}/ocr-status for the result. Needs edit access to the document.")
    @PostMapping("/{versionId}/ocr")
    @PreAuthorize("@authz.hasPermission(" + P + "OS_OCR_TRIGGER)")
    public OcrRerunResponse runOcr(@PathVariable UUID versionId, @RequestBody(required = false) OcrRerunRequest body) {
        List<String> fields = body != null ? body.fields() : null;
        return documentService.prepareRerun(versionId, fields);
    }

    @Operation(summary = "The OCR review queue",
            description = "Every document in this tenant whose OCR failed or whose result needs a "
                    + "human to look at it, newest first, each with the reason. Only the latest "
                    + "version of each document appears -- a reviewer can only act on that one. "
                    + "Lists only documents the caller can see.")
    @GetMapping("/review-queue")
    @PreAuthorize("@authz.hasPermission(" + P + "OS_REVIEW_VIEW_QUEUE)")
    public Page<ReviewQueueItemResponse> getReviewQueue(@PageableDefault(size = 25) Pageable pageable) {
        return documentService.getReviewQueue(pageable);
    }

    @Operation(summary = "Documents whose review has already been saved",
            description = "The counterpart to the queue: what has been settled, newest first. "
                    + "Scoped to what the caller can see, like the queue.")
    @GetMapping("/saved-reviews")
    @PreAuthorize("@authz.hasPermission(" + P + "OS_REVIEW_VIEW_QUEUE)")
    public Page<ReviewQueueItemResponse> getSavedReviews(@PageableDefault(size = 25) Pageable pageable) {
        return documentService.getSavedReviews(pageable);
    }

    @Operation(summary = "Everything the review screen needs for one document",
            description = "Fields with confidence, status and normalized bboxes; the page count; "
                    + "the reasons the document needs review; and its line items. One call rather "
                    + "than four because the screen can render nothing useful until it has all of it.")
    @GetMapping("/{documentId}/review")
    @PreAuthorize("@authz.hasPermission(" + P + "OS_REVIEW_VIEW_QUEUE)")
    public DocumentReviewResponse getReview(@PathVariable UUID documentId) {
        return documentService.getReview(documentId);
    }

    @Operation(summary = "One page of a document, rendered for the review screen",
            description = "A PNG in the same coordinate frame the stored bboxes live in: a "
                    + "highlight drawn at (x0*w, y0*h, x1*w, y1*h) over this image lands on the "
                    + "text. `page` is 0-indexed. X-Page-Width/Height/Count carry the real pixel "
                    + "size, which is NOT dpi x page size -- the renderer caps total area. "
                    + "Rendered on demand through a small bounded cache; nothing is stored.")
    @GetMapping(value = "/{documentId}/pages/{page}/image", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("@authz.hasPermission(" + P + "OS_REVIEW_VIEW_QUEUE)")
    public ResponseEntity<byte[]> getPageImage(@PathVariable UUID documentId, @PathVariable int page) {
        OcrWorkerClient.PageImage image = documentService.getPageImage(documentId, page);
        return ResponseEntity.ok()
                .header("X-Page-Width", String.valueOf(image.widthPx()))
                .header("X-Page-Height", String.valueOf(image.heightPx()))
                .header("X-Page-Count", String.valueOf(image.pageCount()))
                // A version is immutable once written, so its rendered pages
                // are too -- and the reviewer pages back and forth. Private,
                // because this is one tenant's document.
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .contentType(MediaType.IMAGE_PNG)
                .body(image.png());
    }

    @Operation(summary = "Read a region the reviewer selected on the page",
            description = "OCRs the given normalized box and returns what it says. Stores nothing "
                    + "-- deliberately not the worker's finalizing rubber-band route, which would "
                    + "replace the document's entire field set with this one crop's result.")
    @PostMapping("/{documentId}/read-region")
    @PreAuthorize("@authz.hasPermission(" + P + "OS_REVIEW_VIEW_QUEUE)")
    public RubberBandResponse readRegion(@PathVariable UUID documentId, @RequestBody RubberBandRequest request) {
        return documentService.readRegion(documentId, request);
    }

    @Operation(summary = "Save a reviewer's decisions about a document's fields",
            description = "Records what the reviewer accepted and what they corrected, keeping "
                    + "OCR's own value and confidence alongside theirs so extraction accuracy can "
                    + "be measured from real usage. Corrected values are written back to the "
                    + "document's metadata. The document leaves the review queue only once every "
                    + "field that needed attention has been settled. Needs edit access.")
    @PostMapping("/{documentId}/review")
    @PreAuthorize("@authz.hasPermission(" + P + "OS_REVIEW_RESOLVE)")
    public SaveReviewResponse saveReview(@PathVariable UUID documentId, @RequestBody SaveReviewRequest request) {
        return documentService.saveReview(documentId, request);
    }

    @Operation(summary = "Poll a document's OCR status and per-field extraction results")
    @GetMapping("/{documentId}/ocr-status")
    @PreAuthorize("@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)")
    public OcrStatusResponse getOcrStatus(@PathVariable UUID documentId) {
        return documentService.getOcrStatus(documentId);
    }

    @Operation(summary = "Get a document's currently saved metadata field values",
            description = "field_key -> value (a String, or a List<String> for a multi_select field). "
                    + "A field with no saved value is simply absent from the response.")
    @GetMapping("/{documentId}/metadata")
    @PreAuthorize("@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)")
    public Map<String, Object> getMetadata(@PathVariable UUID documentId) {
        return documentMetadataService.getValues(documentId);
    }

    @Operation(summary = "Save a document's metadata field values",
            description = "Replaces whatever was previously saved for each field key present in the "
                    + "body, validated against the document type's mandatory fields. Returns the full "
                    + "saved state (same shape as GET).")
    @PutMapping("/{documentId}/metadata")
    @PreAuthorize("@authz.hasPermission(" + P + "OS_OCR_EDIT_TEXT)")
    public Map<String, Object> saveMetadata(@PathVariable UUID documentId, @RequestBody Map<String, Object> values) {
        documentMetadataService.saveValues(documentId, values, true);
        return documentMetadataService.getValues(documentId);
    }

    /**
     * The OCR field selection, as the Scan Documents form sends it: a JSON
     * array of field keys in a multipart field. Parsed here rather than bound
     * as a List so a malformed value is a field-level validation error the form
     * can show, not a 400 about the request as a whole.
     */
    private List<String> parseRequestedFields(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new MetadataValidationFailedException(List.of(new ValidationError(
                    "requestedFields", "INVALID_JSON", "requestedFields is not valid JSON: " + e.getOriginalMessage(), "error")));
        }
    }
}
