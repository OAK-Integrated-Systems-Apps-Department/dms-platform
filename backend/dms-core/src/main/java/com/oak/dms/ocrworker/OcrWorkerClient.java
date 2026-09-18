package com.oak.dms.ocrworker;

import com.oak.dms.common.exception.UpstreamServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

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

    @SuppressWarnings("unchecked")
    public Map<String, Object> processVersion(UUID versionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", properties.getInternalToken());
        try {
            ResponseEntity<Map> response = ocrWorkerRestTemplate.postForEntity(
                    properties.getBaseUrl() + "/ocr/process/" + versionId,
                    new HttpEntity<>(null, headers),
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
}
