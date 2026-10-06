package com.oak.dms.document;

import com.oak.dms.document.dto.DocumentResponse;
import com.oak.dms.document.dto.DocumentSummaryResponse;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.io.InputStream;
import java.util.UUID;

/**
 * Depends only on {@link com.oak.dms.storage.service.ObjectStorageServiceFactory}/
 * {@link com.oak.dms.storage.service.StorageLocationResolver} - never on a storage
 * vendor SDK or {@code java.nio.file}. See docs/storage-architecture.md.
 * <p>
 * Uploads never create a document directly: they go through the malware scan
 * gate ({@link com.oak.dms.scanning.service.IngestionGate}), which creates the
 * document only once the file is clean - see docs/scanning-architecture.md.
 */
public interface DocumentService {

    /**
     * Submits an upload to the scan gate and returns its ingestion ticket.
     * With a synchronous engine (ClamAV) the ticket is usually already
     * {@code clean} (with a documentId) or rejected; with a cloud scanner it
     * is {@code pending} and the caller polls the ticket. The client's
     * Content-Type is not used - the gate derives the type from content.
     */
    IngestionTicketResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                           long contentLength, InputStream content);

    DocumentResponse getDocument(UUID documentId);

    /**
     * The caller's tenant's documents, newest first, optionally narrowed by
     * a title search and a document type, and leaving out one document
     * (e.g. the one under approval when picking supporting documents).
     */
    Page<DocumentSummaryResponse> listDocuments(String search, UUID documentTypeId, UUID excludeDocumentId,
                                                Pageable pageable);

    /** Streams the document's current version (falling back to its latest version if none is current yet). */
    DownloadedDocument downloadDocument(UUID documentId);

    /** Soft-delete only - see this interface's implementation for why no physical object is purged. */
    void deleteDocument(UUID documentId);
}
