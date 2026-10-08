package com.oak.dms.document;


import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.dto.FolderDtos.DocumentAccessResponse;
import com.oak.dms.folder.dto.FolderDtos.UpdateFolderAccessRequest;
import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.document.dto.DocumentListItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;
import java.util.Map;
import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DocumentSummaryResponse;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.scanning.controller.IngestionTicketResponses;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * {@code Controller -> DocumentService -> ObjectStorageService -> adapter ->
 * provider} - this controller never touches a storage vendor SDK or
 * {@code java.nio.file} itself; every byte crosses through
 * {@link DocumentService}.
 */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Document upload, download, and lifecycle")
public class DocumentController {

    private static final int MAX_PAGE_SIZE = 50;

    private final DocumentService documentService;
    private final DocumentAccessService documentAccessService;
    private final DocumentVersionService versionService;
    private final DocumentRenditionService renditionService;
    private final DuplicateFinder duplicateFinder;

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
                                                                   boolean refuseDuplicates)
            throws IOException {
        try (InputStream content = file.getInputStream()) {
            IngestionTicketResponse ticket = documentService.uploadDocument(documentTypeId, title,
                    file.getOriginalFilename(), file.getSize(), content, folderId, refuseDuplicates);
            return IngestionTicketResponses.toResponseEntity(ticket);
        }
    }

    @GetMapping
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)")

    @Operation(summary = "Browse documents",
            description = "folderId: one folder's documents; without it, documents not in any folder - or every "
                    + "document with all=true. q searches titles. Newest first by default.")
    public PageResponse<DocumentListItem> listDocuments(
            @RequestParam(value = "folderId", required = false) UUID folderId,
            @RequestParam(value = "all", defaultValue = "false") boolean all,
            @RequestParam(value = "q", required = false) String query,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(documentService.listDocuments(folderId, all, query, pageable));
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
    
    @Operation(summary = "List documents, newest first",
            description = "search matches the title (case-insensitive); documentTypeId narrows to one type; "
                    + "excludeDocumentId leaves one document out. size is at most 50.")
    public PageResponse<DocumentSummaryResponse> listDocuments(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID documentTypeId,
            @RequestParam(required = false) UUID excludeDocumentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(documentService.listDocuments(search, documentTypeId, excludeDocumentId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))));

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
}
