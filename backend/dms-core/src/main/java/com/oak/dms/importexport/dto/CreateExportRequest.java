package com.oak.dms.importexport.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What to export - stored as the job's request_params. Either
 * {@code documentIds} (chosen documents) or {@code documentTypeId}
 * optionally narrowed by a created-date range (inclusive, the requester's
 * calendar dates taken as UTC).
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
        Boolean includeFiles
) {

    public boolean filesIncluded() {
        return includeFiles == null || includeFiles;
    }

    public boolean byIds() {
        return documentIds != null && !documentIds.isEmpty();
    }
}
