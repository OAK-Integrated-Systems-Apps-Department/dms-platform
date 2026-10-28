package com.oak.dms.importexport.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What to export - stored as the job's request_params. Either
 * {@code documentIds} (chosen documents), a {@code folderId} (with its
 * subfolders unless {@code includeSubfolders} is false), or
 * {@code documentTypeId} optionally narrowed by a created-date range
 * (inclusive, the requester's calendar dates taken as UTC).
 * {@code hiddenFolderIds} and {@code hiddenDocumentIds} are server-set - see {@link #withHidden}.
 *
 * @param metadataFormat xlsx (default) or csv - the metadata sheet in the ZIP
 * @param includeFiles   false for the metadata sheet only (a report); default true
 */
public record CreateExportRequest(
        @Size(max = 1000) List<UUID> documentIds,
        UUID documentTypeId,
        LocalDate createdFrom,
        LocalDate createdTo,
        String metadataFormat,
        Boolean includeFiles,
        UUID folderId,
        Boolean includeSubfolders,
        List<UUID> hiddenFolderIds,
        List<UUID> hiddenDocumentIds
) {

    public CreateExportRequest(List<UUID> documentIds, UUID documentTypeId, LocalDate createdFrom, LocalDate createdTo,
                               String metadataFormat, Boolean includeFiles) {
        this(documentIds, documentTypeId, createdFrom, createdTo, metadataFormat, includeFiles, null, null);
    }

    public CreateExportRequest(List<UUID> documentIds, UUID documentTypeId, LocalDate createdFrom, LocalDate createdTo,
                               String metadataFormat, Boolean includeFiles, UUID folderId, Boolean includeSubfolders) {
        this(documentIds, documentTypeId, createdFrom, createdTo, metadataFormat, includeFiles, folderId,
                includeSubfolders, null, null);
    }

    /**
     * The same request, set to leave out what's hidden from the requester:
     * documents in these folders (unless they have their own list, V53) and
     * these restricted documents (V54). Always set by the server when the
     * export is requested, whatever the client sent, so the background worker
     * exports only what the requester could see.
     */
    public CreateExportRequest withHidden(Collection<UUID> folders, Collection<UUID> documents) {
        return new CreateExportRequest(documentIds, documentTypeId, createdFrom, createdTo, metadataFormat,
                includeFiles, folderId, includeSubfolders, List.copyOf(folders), List.copyOf(documents));
    }

    public Set<UUID> hiddenFolders() {
        return hiddenFolderIds == null ? Set.of() : Set.copyOf(hiddenFolderIds);
    }

    public Set<UUID> hiddenDocuments() {
        return hiddenDocumentIds == null ? Set.of() : Set.copyOf(hiddenDocumentIds);
    }

    public boolean byFolder() {
        return !byIds() && folderId != null;
    }

    public boolean subfoldersIncluded() {
        return includeSubfolders == null || includeSubfolders;
    }

    public boolean filesIncluded() {
        return includeFiles == null || includeFiles;
    }

    public boolean byIds() {
        return documentIds != null && !documentIds.isEmpty();
    }
}
