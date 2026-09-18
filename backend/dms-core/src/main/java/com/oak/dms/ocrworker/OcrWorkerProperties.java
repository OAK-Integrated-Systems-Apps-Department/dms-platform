package com.oak.dms.ocrworker;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ocr-worker is internal-only -- no published host port, no auth of its
 * own (see backend/workers/ocr-worker/README.md "Auth &amp; service
 * exposure"). dms-core is its one intended caller: base-url points at its
 * docker service name, and internal-token is the shared secret ocr-worker
 * checks (X-Internal-Token) on every /ocr/* request as defense in depth --
 * this value must equal ocr-worker's own INTERNAL_SERVICE_TOKEN.
 * <p>
 * Read timeout is deliberately long (default 60s): OCR is real inference
 * work (Docling + PaddleOCR), not a quick lookup.
 */
@Component
@ConfigurationProperties(prefix = "ocr-worker")
@Getter
@Setter
public class OcrWorkerProperties {
    private String baseUrl;
    private String internalToken;
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 60000;
}
