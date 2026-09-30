package com.oak.dms.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.MetadataValidationFailedException;
import com.oak.dms.document.dto.DocumentReviewResponse;
import com.oak.dms.document.dto.DocumentVersionResponse;
import com.oak.dms.document.dto.OcrRerunRequest;
import com.oak.dms.document.dto.OcrRerunResponse;
import com.oak.dms.document.dto.OcrStatusResponse;
import com.oak.dms.document.dto.ReviewQueueItemResponse;
import com.oak.dms.document.dto.RubberBandRequest;
import com.oak.dms.document.dto.RubberBandResponse;
import com.oak.dms.document.dto.SaveReviewRequest;
import com.oak.dms.document.dto.SaveReviewResponse;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.ocrworker.OcrWorkerClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Uploads a document (as its first version) and queues it for OCR.
 * Phase 1 B.1: {@code upload}/{@code runOcr} no longer call ocr-worker
 * directly -- {@link DocumentServiceImpl#uploadDocument}/{@code prepareRerun}
 * insert a {@code processing_jobs} row + {@code document.version.ocr.requested}
 * outbox event transactionally, and ocr-worker's own RabbitMQ consumer
 * (backend/workers/ocr-worker/src/ocr_worker/consumer.py's
 * {@code OcrRequestedConsumer}) picks it up from there. No @PreAuthorize
 * beyond the blanket {@code anyRequest().authenticated()} in SecurityConfig
 * -- any authenticated user may upload/OCR within their own tenant
 * (enforced in {@link DocumentServiceImpl#uploadDocument}); there is no
 * finer-grained document permission model yet.
 * <p>
 * The caller polls {@code getOcrStatus} for the result, same as before.
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

        return ResponseEntity.created(URI.create("/api/v1/documents/" + response.versionId())).body(response);
    }

    @Operation(summary = "Re-run OCR for specific fields (or, with no body, everything already requested) on a document version",
            description = "Runs asynchronously -- returns an acceptance ack, not the OCR result. Poll "
                    + "GET /{documentId}/ocr-status for the result.")
    @PostMapping("/{versionId}/ocr")
    public OcrRerunResponse runOcr(@PathVariable UUID versionId, @RequestBody(required = false) OcrRerunRequest body) {
        List<String> fields = body != null ? body.fields() : null;
        return documentService.prepareRerun(versionId, fields);
    }

    @Operation(summary = "The OCR review queue",
            description = "Every document in this tenant whose OCR failed or whose result needs a "
                    + "human to look at it, newest first, each with the reason. Only the latest "
                    + "version of each document appears -- a reviewer can only act on that one.")
    @GetMapping("/review-queue")
    public Page<ReviewQueueItemResponse> getReviewQueue(
            @PageableDefault(size = 25) Pageable pageable) {
        return documentService.getReviewQueue(pageable);
    }

    @Operation(summary = "Everything the review screen needs for one document",
            description = "Fields with confidence, status and normalized bboxes; the page count; "
                    + "the reasons the document needs review; and its line items. One call rather "
                    + "than four because the screen can render nothing useful until it has all of it.")
    @GetMapping("/{documentId}/review")
    public DocumentReviewResponse getReview(@PathVariable UUID documentId) {
        return documentService.getReview(documentId);
    }

    @Operation(summary = "One page of a document, rendered for the review screen",
            description = "A PNG in the same coordinate frame the stored bboxes live in: a "
                    + "highlight drawn at (x0*w, y0*h, x1*w, y1*h) over this image lands on the "
                    + "text. `page` is 0-indexed. X-Page-Width/Height/Count carry the real pixel "
                    + "size, which is NOT dpi x page size -- the renderer caps total area.")
    @GetMapping(value = "/{documentId}/pages/{page}/image", produces = MediaType.IMAGE_PNG_VALUE)
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
    public RubberBandResponse readRegion(
            @PathVariable UUID documentId, @RequestBody RubberBandRequest request) {
        return documentService.readRegion(documentId, request);
    }

    @Operation(summary = "Save a reviewer's decisions about a document's fields",
            description = "Records what the reviewer accepted and what they corrected, keeping "
                    + "OCR's own value and confidence alongside theirs so extraction accuracy can "
                    + "be measured from real usage. Corrected values are written back to the "
                    + "document's metadata. The document leaves the review queue only once every "
                    + "field that needed attention has been settled.")
    @PostMapping("/{documentId}/review")
    public SaveReviewResponse saveReview(
            @PathVariable UUID documentId, @RequestBody SaveReviewRequest request) {
        return documentService.saveReview(documentId, request);
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
