package com.oak.dms.scanning.dto;

import com.oak.dms.scanning.enums.IngestionKind;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Everything the ingestion gate needs to scan an upload and, once it is
 * clean, create the right document or version. Built by the caller through
 * {@link #newDocument}/{@link #newVersion} - the only two shapes a
 * {@code DocumentVersion} can ever be created from.
 *
 * @param content read exactly once, while staging; never closed by the gate
 */
public record IngestionRequest(
        IngestionKind kind,
        String createdReason,
        UUID documentTypeId,
        UUID targetDocumentId,
        String title,
        String originalFilename,
        long contentLength,
        InputStream content,
        UUID folderId,
        String versionComment,
        boolean refuseDuplicates,
        /**
         * Which schema fields OCR should extract once this file is clean, or
         * null/empty for "no OCR". Carried on the request rather than acted on
         * at upload: nothing may read an unscanned file, so OCR can only start
         * after the gate promotes a clean version. The gate persists this onto
         * the scan result, and {@code DocumentVersionPromoter} hands it to
         * {@code OcrEnqueueService} at promotion.
         */
        List<String> ocrRequestedFields,
        /** True when this came from a bulk-import batch: queued at low priority so interactive scans aren't starved. */
        boolean ocrLowPriority
) {

    /**
     * @param refuseDuplicates the uploader checked for duplicates and found none: refuse (409) if the same
     *                         file reached the same folder meanwhile, rather than create a second copy
     */
    public IngestionRequest(IngestionKind kind, String createdReason, UUID documentTypeId, UUID targetDocumentId,
                            String title, String originalFilename, long contentLength, InputStream content,
                            UUID folderId, String versionComment) {
        this(kind, createdReason, documentTypeId, targetDocumentId, title, originalFilename, contentLength, content,
                folderId, versionComment, false, null, false);
    }

    public IngestionRequest(IngestionKind kind, String createdReason, UUID documentTypeId, UUID targetDocumentId,
                            String title, String originalFilename, long contentLength, InputStream content,
                            UUID folderId, String versionComment, boolean refuseDuplicates) {
        this(kind, createdReason, documentTypeId, targetDocumentId, title, originalFilename, contentLength, content,
                folderId, versionComment, refuseDuplicates, null, false);
    }

    /** A new document whose uploader asked to refuse it if the same file arrived meanwhile. */
    public IngestionRequest refusingDuplicates() {
        return new IngestionRequest(kind, createdReason, documentTypeId, targetDocumentId, title, originalFilename,
                contentLength, content, folderId, versionComment, true, ocrRequestedFields, ocrLowPriority);
    }

    /** The same request, with OCR asked for on the version this produces. */
    public IngestionRequest withOcr(List<String> requestedFields, boolean lowPriority) {
        return new IngestionRequest(kind, createdReason, documentTypeId, targetDocumentId, title, originalFilename,
                contentLength, content, folderId, versionComment, refuseDuplicates, requestedFields, lowPriority);
    }

    public IngestionRequest(IngestionKind kind, String createdReason, UUID documentTypeId, UUID targetDocumentId,
                            String title, String originalFilename, long contentLength, InputStream content) {
        this(kind, createdReason, documentTypeId, targetDocumentId, title, originalFilename, contentLength, content,
                null, null);
    }

    public static IngestionRequest newDocument(UUID documentTypeId, String title, String originalFilename,
                                               long contentLength, InputStream content) {
        return newDocument(documentTypeId, title, originalFilename, contentLength, content, null);
    }

    /** @param folderId the folder the document is filed in once clean; null = not in a folder */
    public static IngestionRequest newDocument(UUID documentTypeId, String title, String originalFilename,
                                               long contentLength, InputStream content, UUID folderId) {
        return new IngestionRequest(IngestionKind.new_document, "initial_upload", documentTypeId, null, title,
                originalFilename, contentLength, content, folderId, null);
    }

    /** @param createdReason one of {@code replacement_upload}, {@code workflow_update}, {@code signed_copy}, {@code migration}, {@code rollback} */
    public static IngestionRequest newVersion(UUID documentId, String createdReason, String originalFilename,
                                              long contentLength, InputStream content) {
        return newVersion(documentId, createdReason, originalFilename, contentLength, content, null);
    }

    /** @param versionComment what changed, shown in the version history; may be null */
    public static IngestionRequest newVersion(UUID documentId, String createdReason, String originalFilename,
                                              long contentLength, InputStream content, String versionComment) {
        return new IngestionRequest(IngestionKind.new_version, createdReason, null, documentId, null,
                originalFilename, contentLength, content, null, versionComment);
    }
}
