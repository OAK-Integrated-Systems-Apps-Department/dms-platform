package com.oak.dms.document;

import com.oak.dms.document.dto.DocumentVersionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * Uploads a document (as its first version) and runs OCR on it via
 * ocr-worker's internal-only API. This is the dms-core passthrough route
 * backend/workers/ocr-worker/README.md flags as not existing yet --
 * frontend -&gt; dms-core (Keycloak-authenticated, this controller) -&gt;
 * ocr-worker (internal-only, {@link com.oak.dms.ocrworker.OcrWorkerClient}).
 * No @PreAuthorize beyond the blanket {@code anyRequest().authenticated()}
 * in SecurityConfig -- any authenticated user may upload/OCR within their
 * own tenant (enforced in {@link DocumentServiceImpl#runOcr}); there is no
 * finer-grained document permission model yet.
 */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Document upload and OCR (ocr-worker passthrough)")
public class DocumentController {

    private final DocumentService documentService;

    @Operation(summary = "Upload a document as its first version")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentVersionResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "documentType", defaultValue = "general") String documentType) {
        DocumentVersionResponse response = documentService.uploadDocument(file, documentType);
        return ResponseEntity.created(URI.create("/api/v1/documents/" + response.versionId())).body(response);
    }

    @Operation(summary = "Run OCR on a document version via ocr-worker and return its result directly")
    @PostMapping("/{versionId}/ocr")
    public Map<String, Object> runOcr(@PathVariable UUID versionId) {
        return documentService.runOcr(versionId);
    }
}
