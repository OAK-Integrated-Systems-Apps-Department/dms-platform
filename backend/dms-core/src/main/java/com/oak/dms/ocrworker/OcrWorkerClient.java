package com.oak.dms.ocrworker;

import com.oak.dms.common.exception.StructuredExtractionUnavailableException;
import com.oak.dms.common.exception.UpstreamServiceException;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calls ocr-worker's dev-trigger endpoint (POST /ocr/process/{version_id})
 * synchronously and relays its exact JSON output back to the caller -- see
 * backend/workers/ocr-worker/README.md "Output contract". This is the
 * dms-core passthrough route that README explicitly flagged as not
 * existing yet. The real production path (RabbitMQ-triggered, once
 * scan-worker exists) is unaffected by this synchronous shortcut -- it's
 * a separate trigger into the exact same OcrPipeline code on ocr-worker's
 * side, not a replacement for it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcrWorkerClient {

    private final RestTemplate ocrWorkerRestTemplate;
    private final OcrWorkerProperties properties;

    /**
     * @param requestedFields which of the document type's schema fields
     *                        ocr-worker should attempt to extract (see
     *                        selectable-OCR-field-extraction, V35) --
     *                        dms-core owns the field catalog, so each
     *                        entry's {@code dataType}/{@code options} is
     *                        sent alongside its {@code fieldKey} rather
     *                        than making ocr-worker query for them itself.
     *                        Sent as this request's JSON body, {@code
     *                        {"requested_fields": [{"field_key":...,
     *                        "data_type":..., "options":[...]}]}} --
     *                        matching ocr-worker's {@code RequestedField}
     *                        pydantic model (api.py) exactly; a plain list
     *                        of field-key strings would fail that model's
     *                        validation (it requires field_key/data_type
     *                        per entry, not a bare string).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> processVersion(UUID versionId, List<DocumentTypeSchemaResponse.SchemaField> requestedFields) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", properties.getInternalToken());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        List<Map<String, Object>> requestedFieldSpecs = (requestedFields != null ? requestedFields : List.<DocumentTypeSchemaResponse.SchemaField>of())
                .stream()
                .map(field -> {
                    Map<String, Object> spec = new LinkedHashMap<>();
                    spec.put("field_key", field.fieldKey());
                    spec.put("data_type", field.dataType());
                    spec.put("options", field.options().stream().map(o -> o.optionValue()).toList());
                    return spec;
                })
                .toList();
        Map<String, Object> body = Map.of("requested_fields", requestedFieldSpecs);
        try {
            ResponseEntity<Map> response = ocrWorkerRestTemplate.postForEntity(
                    properties.getBaseUrl() + "/ocr/process/" + versionId,
                    new HttpEntity<>(body, headers),
                    Map.class);
            return (Map<String, Object>) response.getBody();
        } catch (HttpStatusCodeException e) {
            log.error("ocr-worker rejected OCR request for version {}: {} {}", versionId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new UpstreamServiceException(
                    "ocr-worker returned " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            log.error("Could not reach ocr-worker at {}: {}", properties.getBaseUrl(), e.getMessage());
            throw new UpstreamServiceException("Could not reach ocr-worker: " + e.getMessage(), e);
        }
    }

    /**
     * The newest structured (local-LLM) extraction result for a version --
     * ocr-worker's GET /ocr/structured/{version_id}, see its README
     * "Structured JSON extraction (local LLM)". Its {@code status} is
     * pending/completed/partial/failed/skipped. Returns null when ocr-worker
     * has none for this version (its 404): OCR hasn't finished, or
     * structured extraction is switched off there.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getStructuredResult(UUID versionId) {
        try {
            ResponseEntity<Map> response = ocrWorkerRestTemplate.exchange(
                    properties.getBaseUrl() + "/ocr/structured/" + versionId,
                    HttpMethod.GET,
                    new HttpEntity<>(internalHeaders()),
                    Map.class);
            return (Map<String, Object>) response.getBody();
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (HttpStatusCodeException e) {
            log.error("ocr-worker rejected structured-result request for version {}: {}", versionId, e.getStatusCode());
            throw new UpstreamServiceException("ocr-worker returned " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            log.error("Could not reach ocr-worker at {}: {}", properties.getBaseUrl(), e.getMessage());
            throw new UpstreamServiceException("Could not reach ocr-worker: " + e.getMessage(), e);
        }
    }

    /**
     * Queues structured extraction again over the version's stored OCR
     * output (no OCR re-run) -- ocr-worker's POST /ocr/structured/{version_id}
     * with wait=false, which answers 202 with the {@code pending} result
     * immediately; the caller polls {@link #getStructuredResult}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> requestStructuredExtraction(UUID versionId) {
        HttpHeaders headers = internalHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<Map> response = ocrWorkerRestTemplate.postForEntity(
                    properties.getBaseUrl() + "/ocr/structured/" + versionId,
                    new HttpEntity<>(Map.of("wait", false), headers),
                    Map.class);
            return (Map<String, Object>) response.getBody();
        } catch (HttpClientErrorException.Conflict e) {
            throw new StructuredExtractionUnavailableException(detailOf(e));
        } catch (HttpStatusCodeException e) {
            log.error("ocr-worker rejected structured-extraction request for version {}: {}", versionId, e.getStatusCode());
            throw new UpstreamServiceException("ocr-worker returned " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            log.error("Could not reach ocr-worker at {}: {}", properties.getBaseUrl(), e.getMessage());
            throw new UpstreamServiceException("Could not reach ocr-worker: " + e.getMessage(), e);
        }
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", properties.getInternalToken());
        return headers;
    }

    /** FastAPI error bodies are {"detail": "..."}; fall back to the raw body. */
    private static String detailOf(HttpStatusCodeException e) {
        try {
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            if (body != null && body.get("detail") instanceof String detail) {
                return detail;
            }
        } catch (RuntimeException ignored) {
            // not JSON -- fall through to the raw body
        }
        return e.getResponseBodyAsString();
    }
}
