package com.oak.dms.importexport.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Narrows the documents a bulk export lists or selects. Every field is
 * optional; the ones given are combined with AND.
 *
 * @param createdFrom   documents created at or after this instant
 * @param createdTo     documents created before this instant (exclusive)
 * @param uploadedBy    the user who uploaded the document
 * @param titleContains case-insensitive substring of the title
 * @param importBatchId documents created by this bulk import
 */
public record BulkExportFilters(
        UUID documentTypeId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdTo,
        UUID uploadedBy,
        String titleContains,
        UUID importBatchId
) {

    public static BulkExportFilters none() {
        return new BulkExportFilters(null, null, null, null, null, null);
    }
}
