package com.oak.dms.document;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * dms-core passthrough for ocr-worker's structured JSON extraction. Same
 * access model as {@link DocumentController}: any authenticated user,
 * within their own tenant (enforced in {@link DocumentStructuredDataServiceImpl}).
 * Structured extraction runs automatically after every OCR run; these
 * routes read the result and re-queue it.
 */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Document upload, OCR (ocr-worker passthrough), and metadata values")
public class DocumentStructuredDataController {

    private final DocumentStructuredDataService structuredDataService;

    @Operation(summary = "Get the structured JSON extracted from a document's latest version",
            description = "Produced by ocr-worker's local LLM after OCR: document_type, fields (label -> value), "
                    + "tables (headers + rows), sections. status is pending | completed | partial | failed | "
                    + "skipped; failed/skipped carry error.code (e.g. MODEL_UNAVAILABLE, LLM_TIMEOUT, "
                    + "EMPTY_OCR_RESULT). Values not found in the document text are null. 404 until OCR has "
                    + "finished and structured extraction has been queued.")
    @GetMapping("/{documentId}/structured-data")
    public Map<String, Object> getStructuredData(@PathVariable UUID documentId) {
        return structuredDataService.getStructuredData(documentId);
    }

    @Operation(summary = "Re-run structured extraction on a document's latest OCR output",
            description = "Does not re-run OCR. Returns 202 with the pending result; poll "
                    + "GET /{documentId}/structured-data. 409 if structured extraction is disabled or the "
                    + "document has no OCR output yet.")
    @PostMapping("/{documentId}/structured-data")
    public ResponseEntity<Map<String, Object>> requestStructuredExtraction(@PathVariable UUID documentId) {
        return ResponseEntity.accepted().body(structuredDataService.requestStructuredExtraction(documentId));
    }
}
