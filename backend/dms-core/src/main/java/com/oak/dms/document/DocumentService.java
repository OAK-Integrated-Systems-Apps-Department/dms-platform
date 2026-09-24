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
     *                        {@code "pending"} and a {@code processing_jobs}
     *                        row + {@code document.version.ocr.requested}
     *                        outbox event are inserted in this SAME
     *                        transaction (Phase 1 B.1) -- ocr-worker's own
     *                        RabbitMQ consumer picks it up from there;
     *                        nothing further needs to be called after this
     *                        method returns.
     */
    DocumentVersionResponse uploadDocument(MultipartFile file, String documentTypeName, Map<String, Object> metadataValues,
                                            List<String> requestedFields);

    /**
     * Validates {@code fields} (subset-of-schema only -- unlike upload, a
     * re-run of a single optional field must be allowed without also
     * re-requesting every mandatory field), unions them into the version's
     * stored {@code requested_fields}, and flips {@code ocr_status} back to
     * {@code "pending"}. In the same transaction, inserts a fresh
     * {@code processing_jobs} row + {@code .requested} outbox event --
     * unless a job for this version is already active (queued/processing),
     * in which case this is a no-op on that front (see
     * DocumentServiceImpl#prepareRerun's own comment on why inserting
     * unconditionally isn't safe). {@code fields} null/empty means
     * "re-run everything already requested on this version".
     */
    OcrRerunResponse prepareRerun(UUID versionId, List<String> fields);

    /**
     * Collapses the document's current version's {@code ocr_status} into
     * {@code "processing" | "completed" | "failed"} and attaches every
     * {@code document_ocr_field_results} row ocr-worker has written for it
     * (see {@link DocumentOcrFieldResult}) -- the poll target for the
     * upload page's progress UI.
     */
    OcrStatusResponse getOcrStatus(UUID documentId);
}
