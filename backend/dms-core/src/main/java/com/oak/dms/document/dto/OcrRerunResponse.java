package com.oak.dms.document.dto;

import java.util.List;
import java.util.UUID;

/**
 * Ack returned by {@code POST /documents/{versionId}/ocr} -- OCR now always
 * runs asynchronously, so this is "the re-run was accepted", not the OCR
 * result itself. {@code effectiveFields} is the full, unioned set of fields
 * now requested on this version (what {@link com.oak.dms.document.DocumentService#triggerOcrAsync}
 * was actually kicked off with).
 */
public record OcrRerunResponse(UUID versionId, String status, List<String> effectiveFields) {
}
