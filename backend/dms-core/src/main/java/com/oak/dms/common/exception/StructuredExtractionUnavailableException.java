package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * ocr-worker refused to (re)run structured extraction for a version: it is
 * disabled there (LLM_EXTRACTION_MODE=off) or the version has no stored OCR
 * output to structure yet. A state conflict the caller can act on, not an
 * infrastructure failure -- so 409, not {@link UpstreamServiceException}'s 502.
 */
public class StructuredExtractionUnavailableException extends ApiException {
    public StructuredExtractionUnavailableException(String message) {
        super(HttpStatus.CONFLICT, "STRUCTURED_EXTRACTION_UNAVAILABLE", message);
    }
}
