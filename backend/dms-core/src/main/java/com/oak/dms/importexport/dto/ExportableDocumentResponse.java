package com.oak.dms.importexport.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A document the caller may export, as the bulk export picker lists it.
 *
 * @param sizeBytes size of the current version
 */
public record ExportableDocumentResponse(UUID documentId, String title, UUID documentTypeId,
                                         String documentTypeName, UUID uploadedById, String uploadedByName,
                                         OffsetDateTime createdAt, Long sizeBytes) {
}
