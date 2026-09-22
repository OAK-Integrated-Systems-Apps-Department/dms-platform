package com.oak.dms.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.MetadataValidationFailedException;
import com.oak.dms.document.dto.DocumentVersionResponse;
import com.oak.dms.document.dto.OcrRerunRequest;
import com.oak.dms.document.dto.OcrRerunResponse;
import com.oak.dms.document.dto.OcrStatusResponse;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.security.auth.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
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
 * own tenant (enforced in {@link DocumentServiceImpl#uploadDocument}); there
 * is no finer-grained document permission model yet.
 * <p>
 * OCR now always runs asynchronously (selectable-OCR-field-extraction):
 * {@code upload} and {@code runOcr} both call {@link DocumentService#triggerOcrAsync}
 * AFTER their own transactional call returns -- i.e. after that transaction
 * has already committed -- and the caller polls {@code getOcrStatus} for the
 * result instead of getting it back synchronously.
 */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Document upload, OCR (ocr-worker passthrough), and metadata values")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentMetadataService documentMetadataService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "Upload a document as its first version",
            description = "metadataValues, if given, is a JSON object (field_key -> value, or "
                    + "field_key -> array of values for a multi_select field) validated against the "
                    + "document type's mandatory fields before anything is written. requestedFields is a "
                    + "JSON array of field_key strings OCR should attempt to extract -- must be a subset "
                    + "of the document type's visible schema fields and must include every mandatory "
                    + "field. OCR is kicked off in the background immediately after upload; poll "
                    + "GET /{documentId}/ocr-status for the result.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentVersionResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "documentType", defaultValue = "general") String documentType,
            @RequestParam(name = "metadataValues", required = false) String metadataValuesJson,
            @RequestParam(name = "requestedFields", required = false) String requestedFieldsJson) {
        Map<String, Object> metadataValues = parseMetadataValues(metadataValuesJson);
        List<String> requestedFields = parseRequestedFields(requestedFieldsJson);

        DocumentVersionResponse response = documentService.uploadDocument(file, documentType, metadataValues, requestedFields);

        // Fired only now, after uploadDocument's own @Transactional method
        // has returned (i.e. committed) -- see DocumentService#triggerOcrAsync's
        // doc comment for why this can't be called from inside that method.
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        documentService.triggerOcrAsync(response.versionId(), requestedFields, tenantId, userId);

        return ResponseEntity.created(URI.create("/api/v1/documents/" + response.versionId())).body(response);
    }

    @Operation(summary = "Re-run OCR for specific fields (or, with no body, everything already requested) on a document version",
            description = "Runs asynchronously -- returns an acceptance ack, not the OCR result. Poll "
                    + "GET /{documentId}/ocr-status for the result.")
    @PostMapping("/{versionId}/ocr")
    public OcrRerunResponse runOcr(@PathVariable UUID versionId, @RequestBody(required = false) OcrRerunRequest body) {
        List<String> fields = body != null ? body.fields() : null;
        OcrRerunResponse response = documentService.prepareRerun(versionId, fields);

        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        documentService.triggerOcrAsync(response.versionId(), response.effectiveFields(), tenantId, userId);

        return response;
    }

    @Operation(summary = "Poll a document's OCR status and per-field extraction results")
    @GetMapping("/{documentId}/ocr-status")
    public OcrStatusResponse getOcrStatus(@PathVariable UUID documentId) {
        return documentService.getOcrStatus(documentId);
    }

    @Operation(summary = "Get a document's currently saved metadata field values",
            description = "field_key -> value (a String, or a List<String> for a multi_select field). "
                    + "A field with no saved value is simply absent from the response.")
    @GetMapping("/{documentId}/metadata")
    public Map<String, Object> getMetadata(@PathVariable UUID documentId) {
        return documentMetadataService.getValues(documentId);
    }

    @Operation(summary = "Save a document's metadata field values",
            description = "Replaces whatever was previously saved for each field key present in the "
                    + "body, validated against the document type's mandatory fields. Returns the full "
                    + "saved state (same shape as GET).")
    @PutMapping("/{documentId}/metadata")
    public Map<String, Object> saveMetadata(@PathVariable UUID documentId, @RequestBody Map<String, Object> values) {
        documentMetadataService.saveValues(documentId, values, true);
        return documentMetadataService.getValues(documentId);
    }

    private Map<String, Object> parseMetadataValues(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return parsed;
        } catch (JsonProcessingException e) {
            throw new MetadataValidationFailedException(List.of(new ValidationError(
                    "metadataValues", "INVALID_JSON", "metadataValues is not valid JSON: " + e.getOriginalMessage(), "error")));
        }
    }

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
