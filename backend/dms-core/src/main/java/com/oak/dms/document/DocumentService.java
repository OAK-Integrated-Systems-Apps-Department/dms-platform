package com.oak.dms.document;

import com.oak.dms.document.dto.DocumentVersionResponse;
import com.oak.dms.document.dto.OcrRerunResponse;
import com.oak.dms.document.dto.OcrStatusResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DocumentService {

    /**
     * @param metadataValues  field_key -> value (String, or List&lt;String&gt;
     *                        for a multi_select field) to save alongside the
     *                        new document, validated against the document
     *                        type's mandatory fields before anything is
     *                        written. Empty/null if the caller submitted none.
     * @param requestedFields which of the document type's schema fields OCR
     *                        should attempt to extract -- must be a subset of
     *                        the schema's visible fields and must include
     *                        every mandatory field (see
     *                        DocumentServiceImpl#validateRequestedFields).
     *                        The new version's {@code ocr_status} is set to
     *                        {@code "processing"} immediately; the caller is
     *                        responsible for kicking off {@link #triggerOcrAsync}
     *                        itself once this method's transaction has
     *                        committed (see that method's own doc comment).
     */
    DocumentVersionResponse uploadDocument(MultipartFile file, String documentTypeName, Map<String, Object> metadataValues,
                                            List<String> requestedFields);

    /**
     * Validates {@code fields} (subset-of-schema only -- unlike upload, a
     * re-run of a single optional field must be allowed without also
     * re-requesting every mandatory field), unions them into the version's
     * stored {@code requested_fields}, and flips {@code ocr_status} back to
     * {@code "processing"}. Returns the resulting effective field list so
     * the caller can pass it straight into {@link #triggerOcrAsync}.
     * {@code fields} null/empty means "re-run everything already requested
     * on this version".
     */
    OcrRerunResponse prepareRerun(UUID versionId, List<String> fields);

    /**
     * Calls ocr-worker (see {@link com.oak.dms.ocrworker.OcrWorkerClient}) in
     * the background and, on success, best-effort maps its {@code fields}
     * onto this document's metadata (see
     * {@link DocumentMetadataService#applyOcrFields}). MUST be called after
     * whatever transaction created/updated this version has already
     * committed -- e.g. from a controller, right after calling
     * {@link #uploadDocument} or {@link #prepareRerun}, never from inside
     * either of those transactional methods -- since this method dispatches
     * to a different thread immediately, and that thread must see the
     * committed row, not a half-written one.
     * <p>
     * {@code tenantId}/{@code userId} are passed explicitly (not read from
     * {@link com.oak.dms.security.auth.TenantContext}) because that context
     * is a plain per-request {@code ThreadLocal} that does not propagate
     * onto the {@code @Async} executor's thread -- the caller must capture
     * both on the original request thread before calling this.
     */
    void triggerOcrAsync(UUID versionId, List<String> requestedFields, UUID tenantId, UUID userId);

    /**
     * Collapses the document's current version's {@code ocr_status} into
     * {@code "processing" | "completed" | "failed"} and attaches every
     * {@code document_ocr_field_results} row ocr-worker has written for it
     * (see {@link DocumentOcrFieldResult}) -- the poll target for the
     * upload page's progress UI.
     */
    OcrStatusResponse getOcrStatus(UUID documentId);
}
