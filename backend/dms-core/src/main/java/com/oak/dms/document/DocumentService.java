package com.oak.dms.document;

import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DownloadedDocument;

import java.io.InputStream;
import java.util.UUID;

/**
 * Depends only on {@link com.oak.dms.storage.service.ObjectStorageServiceFactory}/
 * {@link com.oak.dms.storage.service.StorageLocationResolver} - never on a storage
 * vendor SDK or {@code java.nio.file}. See docs/storage-architecture.md.
 */
public interface DocumentService {

    DocumentResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                     String contentType, long contentLength, InputStream content);

    DocumentResponse getDocument(UUID documentId);

    /** Streams the document's current version (falling back to its latest version if none is current yet). */
    DownloadedDocument downloadDocument(UUID documentId);

    /** Soft-delete only - see this interface's implementation for why no physical object is purged. */
    void deleteDocument(UUID documentId);
}
