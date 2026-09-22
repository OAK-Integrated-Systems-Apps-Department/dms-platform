package com.oak.dms.document.dto;

import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /documents/{id}/ocr-status} response. {@code status} is
 * collapsed to exactly one of "processing" | "completed" | "failed" from
 * the underlying {@code document_versions.ocr_status} value (see
 * DocumentServiceImpl#collapseOcrStatus) -- external contract, the frontend
 * polls on this field.
 */
public record OcrStatusResponse(
        UUID id,
        String status,
        Map<String, OcrFieldResultResponse> fields) {
}
