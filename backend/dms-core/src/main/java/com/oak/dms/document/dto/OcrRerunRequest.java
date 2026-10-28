package com.oak.dms.document.dto;

import java.util.List;

/** Body of {@code POST /documents/{versionId}/ocr}. Null/empty {@code fields} means "re-run every field already requested on this version". */
public record OcrRerunRequest(List<String> fields) {
}
