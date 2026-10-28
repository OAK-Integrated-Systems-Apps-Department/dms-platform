package com.oak.dms.document.dto;

import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /documents/{id}/ocr-status} response. {@code status} is
 * collapsed to exactly one of "queued" | "processing" | "completed" |
 * "failed" from the underlying {@code document_versions.ocr_status} value
 * (see DocumentServiceImpl#collapseOcrStatus) -- external contract, the
 * frontend polls on this field. "queued" (Phase 1 B.8) means the job is
 * waiting for an ocr-worker to claim it; "processing" means one actually
 * has.
 *
 * @param errorCode     ocr-worker's own classified failure code (see its
 *                      errors.py taxonomy, e.g. UNSUPPORTED_FORMAT,
 *                      ENCRYPTED_PDF), parsed from the job's last_error.
 *                      Null unless status is "failed". The frontend
 *                      conditions on this -- retrying an
 *                      UNSUPPORTED_FORMAT can never succeed, so it hides
 *                      the retry affordance for that one specifically.
 * @param friendlyError a short user-facing sentence for that code (see
 *                      {@link com.oak.dms.ocrworker.OcrErrorMessages}) --
 *                      the raw exception text never reaches the client.
 */
public record OcrStatusResponse(
        UUID id,
        String status,
        Map<String, OcrFieldResultResponse> fields,
        String errorCode,
        String friendlyError) {
}
