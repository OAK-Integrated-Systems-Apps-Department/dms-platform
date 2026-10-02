package com.oak.dms.document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns {@code document_ocr_field_results} rows into the sparse
 * {@code {fieldKey: {value, confidence}}} map
 * {@link DocumentMetadataService#applyOcrFields} consumes.
 * <p>
 * This loop previously existed twice, in {@code DocumentServiceImpl#getOcrStatus}
 * and {@code OcrEventsConsumer#applyFieldResults}, with a comment on the
 * second explaining that the duplication was deliberate for "a ~10-line loop
 * this stable" and "kept symmetric on purpose". That reasoning held at two
 * copies. Phase 2's {@code OcrFieldsReconciler} is a third caller, and three
 * copies of a rule about which rows are skipped is one place for them to
 * drift apart -- so it lives here now and all three call it.
 * <p>
 * Deliberately a static helper with no Spring wiring: it has no state, no
 * dependencies, and nothing to inject, so a unit test can call it directly.
 */
public final class OcrFieldResultMapper {

    private OcrFieldResultMapper() {
    }

    /**
     * A row is skipped when it has no value, or when its status is
     * {@code not_found} -- that row exists precisely to record that the
     * field was looked for and missed (see V35's own comment), so it must
     * never become a metadata value. A {@code low_confidence} row is NOT
     * skipped: the value is returned either way and flagged for review,
     * never dropped.
     * <p>
     * The two statuses V45 added need no case of their own, and that is
     * worth stating rather than leaving to be rediscovered:
     * {@code not_extractable} rows always carry a null {@code value} and
     * so fall out on the first clause, and a {@code defaulted} row is a
     * real value the tenant default supplied -- it is meant to be filed,
     * exactly like an extracted one. (What it must NOT do is count as OCR
     * accuracy or trigger review; neither of those decisions is made
     * here.)
     */
    public static Map<String, Object> toOcrFieldsRaw(List<DocumentOcrFieldResult> rows) {
        Map<String, Object> ocrFieldsRaw = new LinkedHashMap<>();
        for (DocumentOcrFieldResult row : rows) {
            if (row.getValue() == null || "not_found".equals(row.getStatus())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("value", row.getValue());
            if (row.getConfidence() != null) {
                entry.put("confidence", row.getConfidence());
            }
            ocrFieldsRaw.put(row.getFieldKey(), entry);
        }
        return ocrFieldsRaw;
    }
}
