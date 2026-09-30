package com.oak.dms.document;

import com.oak.dms.document.dto.DocumentReviewResponse;
import com.oak.dms.document.dto.DocumentVersionResponse;
import com.oak.dms.document.dto.OcrRerunResponse;
import com.oak.dms.document.dto.OcrStatusResponse;
import com.oak.dms.document.dto.ReviewQueueItemResponse;
import com.oak.dms.document.dto.RubberBandRequest;
import com.oak.dms.document.dto.RubberBandResponse;
import com.oak.dms.document.dto.SaveReviewRequest;
import com.oak.dms.document.dto.SaveReviewResponse;
import com.oak.dms.ocrworker.OcrWorkerClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * The review queue (Phase 4b, production prompt 3.13): every latest
     * version in the caller's tenant that failed OCR or came back flagged,
     * newest first, each with a sentence saying why.
     * <p>
     * The reason travels WITH the row deliberately. A queue that lists
     * documents and nothing else forces the reviewer to open every one of
     * them to find out what is wrong, which is most of the work the queue
     * exists to save -- so a failed version carries the friendly message
     * for its classified error code, and a flagged one carries the rule
     * that fired and the fields or pages it fired on.
     */
    Page<ReviewQueueItemResponse> getReviewQueue(Pageable pageable);

    /**
     * Everything the review screen needs to draw itself, in one call
     * (Phase 4c): the fields with their confidences, statuses and
     * normalized boxes, the page count, the reasons the document is in
     * the queue, and the line items.
     */
    DocumentReviewResponse getReview(UUID documentId);

    /**
     * One page of a document rendered as a PNG, in the same frame the
     * stored {@code bbox_norm} values live in -- so a highlight drawn at
     * (x0*w, y0*h, x1*w, y1*h) over this image lands on the text.
     * <p>
     * {@code page} is 0-indexed, matching the worker's own convention and
     * the {@code source.page - 1} the field results carry.
     */
    OcrWorkerClient.PageImage getPageImage(UUID documentId, int page);

    /**
     * What OCR reads in a region the reviewer dragged over the preview.
     * Stores nothing: this answers "what does that say", and only the
     * reviewer's decision about the answer is persisted.
     */
    RubberBandResponse readRegion(UUID documentId, RubberBandRequest request);

    /**
     * Records what the reviewer decided about each field, keeping OCR's
     * own value alongside theirs (Phase 4d, production prompt 3.13) --
     * "so accuracy can be measured from real usage".
     * <p>
     * Corrected values are written back to the document's metadata; both
     * values are kept in {@code document_ocr_corrections} either way, so
     * the record of how often OCR was wrong survives the fix. The document
     * leaves the review queue only when every field that needed attention
     * has been settled -- a queue that empties itself on the first save
     * would be lying.
     */
    SaveReviewResponse saveReview(UUID documentId, SaveReviewRequest request);
}
