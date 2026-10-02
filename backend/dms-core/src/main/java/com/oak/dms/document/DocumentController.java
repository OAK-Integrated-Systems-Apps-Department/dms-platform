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
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.scanning.controller.IngestionTicketResponses;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

    private final DocumentService documentService;
    private final DocumentAccessService documentAccessService;

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
                                                                   @RequestParam(value = "folderId", required = false) UUID folderId)
            throws IOException {
        try (InputStream content = file.getInputStream()) {
            IngestionTicketResponse ticket = documentService.uploadDocument(documentTypeId, title,
                    file.getOriginalFilename(), file.getSize(), content, folderId);
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
