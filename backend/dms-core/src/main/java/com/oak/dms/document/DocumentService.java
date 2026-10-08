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
    default IngestionTicketResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                                   long contentLength, InputStream content) {
        return uploadDocument(documentTypeId, title, originalFilename, contentLength, content, null);
    }

    /**
     * @param folderId the folder to file the document in - checked now to exist and to let the caller
     *                 upload into it (V53 folder access); null = the top level
     */
    /**
     * As {@link #uploadDocument(UUID, String, String, long, InputStream, UUID)}; {@code refuseDuplicates}: the
     * uploader checked for duplicates and found none - refuse (409) if the same file reached the folder meanwhile.
     */
    IngestionTicketResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                           long contentLength, InputStream content, UUID folderId,
                                           boolean refuseDuplicates);

    IngestionTicketResponse uploadDocument(UUID documentTypeId, String title, String originalFilename,
                                           long contentLength, InputStream content, UUID folderId);

    /**
     * {@link #uploadDocument} for a background job (a Bulk Import batch) whose
     * folder access was checked when the person requested it. The folder must
     * still exist, but access isn't checked again: the job runs without the
     * requester's login, so an admin's full access can't be re-established here.
     */
    IngestionTicketResponse uploadDocumentFromJob(UUID documentTypeId, String title, String originalFilename,
                                                  long contentLength, InputStream content, UUID folderId);

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

    /**
     * The caller's tenant's documents, newest first, optionally narrowed by
     * a title search and a document type, and leaving out one document
     * (e.g. the one under approval when picking supporting documents).
     */
    Page<DocumentSummaryResponse> listDocuments(String search, UUID documentTypeId, UUID excludeDocumentId,
                                                Pageable pageable);

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
}
