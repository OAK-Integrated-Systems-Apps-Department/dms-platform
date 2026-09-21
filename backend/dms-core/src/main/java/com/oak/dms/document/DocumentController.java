package com.oak.dms.document;

import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DownloadedDocument;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)")
    @Operation(summary = "Upload a new document (creates its first version)")
    public ResponseEntity<DocumentResponse> uploadDocument(@RequestPart("file") MultipartFile file,
                                                            @RequestParam("documentTypeId") UUID documentTypeId,
                                                            @RequestParam("title") String title) throws IOException {
        try (InputStream content = file.getInputStream()) {
            DocumentResponse response = documentService.uploadDocument(documentTypeId, title,
                    file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            return ResponseEntity.created(URI.create("/api/v1/documents/" + response.documentId())).body(response);
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "Get a document's metadata")
    public DocumentResponse getDocument(@PathVariable("id") UUID id) {
        return documentService.getDocument(id);
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)")
    @Operation(summary = "Download a document's current (or latest) version")
    public ResponseEntity<StreamingResponseBody> downloadDocument(@PathVariable("id") UUID id) {
        DownloadedDocument downloaded = documentService.downloadDocument(id);
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
                        "attachment; filename=\"" + sanitizeForHeader(downloaded.filename()) + "\"")
                .body(body);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_DELETE)")
    @Operation(summary = "Soft-delete a document")
    public ResponseEntity<Void> deleteDocument(@PathVariable("id") UUID id) {
        documentService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }

    /** Strips characters that could break out of the quoted filename in a Content-Disposition header. */
    private static String sanitizeForHeader(String filename) {
        if (filename == null || filename.isBlank()) {
            return "download";
        }
        return filename.replaceAll("[\\r\\n\"]", "_");
    }
}
