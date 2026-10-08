package com.oak.dms.document;

import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DocumentReviewResponse;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.document.dto.OcrRerunResponse;
import com.oak.dms.document.dto.OcrStatusResponse;
import com.oak.dms.document.dto.ReviewQueueItemResponse;
import com.oak.dms.document.dto.RubberBandRequest;
import com.oak.dms.document.dto.RubberBandResponse;
import com.oak.dms.document.dto.SaveReviewRequest;
import com.oak.dms.document.dto.SaveReviewResponse;
import com.oak.dms.ocrworker.OcrWorkerClient;
import com.oak.dms.scanning.dto.IngestionTicketResponse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Depends only on {@link com.oak.dms.storage.service.ObjectStorageServiceFactory}/
 * {@link com.oak.dms.storage.service.StorageLocationResolver} - never on a storage
 * vendor SDK or {@code java.nio.file}. See docs/storage-architecture.md.
 * <p>
 * Uploads never create a document directly: they go through the malware scan
 * gate ({@link com.oak.dms.scanning.service.IngestionGate}), which creates the
 * document only once the file is clean - see docs/scanning-architecture.md.
 * <p>
 * <b>OCR (merge note).</b> The OCR branch used to upload straight to storage
 * and start OCR in the same call. That path is gone: OCR is now started
 * <em>after</em> the gate has returned a clean verdict and the version exists,
 * so no unscanned byte is ever read by the worker. {@code requestedFields}
 * rides along on the upload and is stored on the version; everything else OCR
 * needs is read back from the version row.
 */
public interface DocumentService {

    /**
     * Submits an upload to the scan gate and returns its ingestion ticket.
     * With a synchronous engine (ClamAV) the ticket is usually already
     * {@code clean} (with a documentId) or rejected; with a cloud scanner it
     * is {@code pending} and the caller polls the ticket. The client's
     * Content-Type is not used - the gate derives the type from content.
     */
    default IngestionTicketResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                                   long contentLength, InputStream content) {
        return uploadDocument(documentTypeId, title, originalFilename, contentLength, content, null);
    }

    /**
     * @param folderId the folder to file the document in - checked now to exist and to let the caller
     *                 upload into it (V53 folder access); null = the top level
     */
    IngestionTicketResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                           long contentLength, InputStream content, UUID folderId);

    /**
     * As {@link #uploadDocument(UUID, String, String, long, InputStream, UUID)}; {@code refuseDuplicates}: the
     * uploader checked for duplicates and found none - refuse (409) if the same file reached the folder meanwhile.
     */
    IngestionTicketResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                           long contentLength, InputStream content, UUID folderId,
                                           boolean refuseDuplicates);

    /**
     * As above, plus the OCR field selection made in the Scan Documents flow.
     *
     * @param requestedFields which of the document type's schema fields OCR should attempt to extract -
     *                        a subset of the schema's visible fields, including every mandatory field
     *                        (see {@code DocumentServiceImpl#validateRequestedFields}). Stored on the
     *                        version; OCR is queued only once the gate reports the file clean and the
     *                        version exists. Null or empty means "don't run OCR on this upload".
     */
    IngestionTicketResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                           long contentLength, InputStream content, UUID folderId,
                                           boolean refuseDuplicates, List<String> requestedFields);

    /**
     * {@link #uploadDocument} for a background job (a Bulk Import batch) whose
     * folder access was checked when the person requested it. The folder must
     * still exist, but access isn't checked again: the job runs without the
     * requester's login, so an admin's full access can't be re-established here.
     */
    IngestionTicketResponse uploadDocumentFromJob(UUID documentTypeId, String title, String originalFilename,
                                                  long contentLength, InputStream content, UUID folderId);

    /**
     * {@link #uploadDocumentFromJob} with the batch's OCR field selection. Same
     * rule as the interactive upload: OCR is queued per file only after a clean
     * verdict, and only within the batch cap and per-tenant queue limit (see
     * {@code OcrEnqueueService}).
     */
    IngestionTicketResponse uploadDocumentFromJob(UUID documentTypeId, String title, String originalFilename,
                                                  long contentLength, InputStream content, UUID folderId,
                                                  List<String> requestedFields);

    /**
     * A page of the tenant's documents, newest first.
     *
     * @param folderId only that folder's documents; ignored when {@code allFolders}
     * @param unfiled  with no folderId: only documents not in any folder
     * @param query    optional case-insensitive title search
     */
    org.springframework.data.domain.Page<com.oak.dms.document.dto.DocumentListItem> listDocuments(
            UUID folderId, boolean allFolders, String query, org.springframework.data.domain.Pageable pageable);

    /**
     * "Shared with me": restricted documents whose own list names the caller
     * or one of their roles (not ones they own), wherever they're filed -
     * even in a folder the caller can't open.
     */
    org.springframework.data.domain.Page<com.oak.dms.document.dto.DocumentListItem> listSharedWithMe(
            String query, org.springframework.data.domain.Pageable pageable);

    /** Files the documents in {@code folderId} (null = out of any folder). Returns how many moved. */
    int moveDocuments(java.util.List<UUID> documentIds, UUID folderId);

    DocumentResponse getDocument(UUID documentId);

    /** Streams the document's current version (falling back to its latest version if none is current yet). */
    DownloadedDocument downloadDocument(UUID documentId);

    /** {@link #downloadDocument} for showing it in the browser - audited as DOCUMENT_VIEWED, not DOCUMENT_DOWNLOADED. */
    DownloadedDocument previewDocument(UUID documentId);

    /**
     * {@link #downloadDocument} for a background job (a Bulk Export) whose
     * selection was already limited, when requested, to what the requester
     * could see. Every check but access is applied, and it's audited as a
     * download through that job. Access isn't re-checked: the job runs
     * without the requester's login, so an admin's full access can't be
     * re-established here.
     */
    DownloadedDocument downloadDocumentFromJob(UUID documentId, String job);

    /** Soft-delete only - see this interface's implementation for why no physical object is purged. */
    void deleteDocument(UUID documentId);

    // ---------------------------------------------------------------- OCR
    // Re-applied from the OCR branch unchanged in intent. Every one of these
    // is a READ of a version that already exists and has already passed the
    // scan gate, so none of them needed reshaping for the merge - only their
    // authorization did (see DocumentController: os.* plus folder access).

    /**
     * Validates {@code fields} (subset-of-schema only -- unlike upload, a
     * re-run of a single optional field must be allowed without also
     * re-requesting every mandatory field), unions them into the version's
     * stored {@code requested_fields}, and flips {@code ocr_status} back to
     * {@code "pending"}. In the same transaction, inserts a fresh
     * {@code processing_jobs} row + {@code .requested} outbox event --
     * unless a job for this version is already active (queued/processing),
     * in which case this is a no-op on that front. {@code fields} null/empty
     * means "re-run everything already requested on this version".
     */
    OcrRerunResponse prepareRerun(UUID versionId, List<String> fields);

    /**
     * Collapses the document's current version's {@code ocr_status} into
     * {@code "processing" | "completed" | "failed"} and attaches every
     * {@code document_ocr_field_results} row ocr-worker has written for it --
     * the poll target for the Scan Documents page's progress UI.
     */
    OcrStatusResponse getOcrStatus(UUID documentId);

    /**
     * The review queue: every latest version in the caller's tenant that
     * failed OCR or came back flagged, newest first, each with a sentence
     * saying why - and, since the merge, only documents the caller can
     * actually see (folder access / document access list).
     * <p>
     * The reason travels WITH the row deliberately. A queue that lists
     * documents and nothing else forces the reviewer to open every one of
     * them to find out what is wrong, which is most of the work the queue
     * exists to save.
     */
    Page<ReviewQueueItemResponse> getReviewQueue(Pageable pageable);

    /**
     * Everything the review screen needs to draw itself, in one call: the
     * fields with their confidences, statuses and normalized boxes, the page
     * count, the reasons the document is in the queue, and the line items.
     */
    DocumentReviewResponse getReview(UUID documentId);

    /**
     * Documents in this tenant whose review has been saved - the "Saved
     * reviews" screen. Scoped to what the caller can see, like the queue.
     */
    Page<ReviewQueueItemResponse> getSavedReviews(Pageable pageable);

    /**
     * One page of a document rendered as a PNG, in the same frame the
     * stored {@code bbox_norm} values live in -- so a highlight drawn at
     * (x0*w, y0*h, x1*w, y1*h) over this image lands on the text.
     * <p>
     * {@code page} is 0-indexed, matching the worker's own convention and
     * the {@code source.page - 1} the field results carry. Rendered on
     * demand through a small bounded cache; nothing is persisted.
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
     * own value alongside theirs -- "so accuracy can be measured from real
     * usage".
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
