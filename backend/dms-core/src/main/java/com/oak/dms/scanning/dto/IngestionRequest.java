package com.oak.dms.scanning.dto;

import com.oak.dms.scanning.enums.IngestionKind;

import java.io.InputStream;
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
        UUID folderId
) {

    public IngestionRequest(IngestionKind kind, String createdReason, UUID documentTypeId, UUID targetDocumentId,
                            String title, String originalFilename, long contentLength, InputStream content) {
        this(kind, createdReason, documentTypeId, targetDocumentId, title, originalFilename, contentLength, content, null);
    }

    public static IngestionRequest newDocument(UUID documentTypeId, String title, String originalFilename,
                                               long contentLength, InputStream content) {
        return newDocument(documentTypeId, title, originalFilename, contentLength, content, null);
    }

    /** @param folderId the folder the document is filed in once clean; null = not in a folder */
    public static IngestionRequest newDocument(UUID documentTypeId, String title, String originalFilename,
                                               long contentLength, InputStream content, UUID folderId) {
        return new IngestionRequest(IngestionKind.new_document, "initial_upload", documentTypeId, null, title,
                originalFilename, contentLength, content, folderId);
    }

    /** @param createdReason one of {@code replacement_upload}, {@code workflow_update}, {@code signed_copy}, {@code migration}, {@code rollback} */
    public static IngestionRequest newVersion(UUID documentId, String createdReason, String originalFilename,
                                              long contentLength, InputStream content) {
        return new IngestionRequest(IngestionKind.new_version, createdReason, null, documentId, null,
                originalFilename, contentLength, content);
    }
}
