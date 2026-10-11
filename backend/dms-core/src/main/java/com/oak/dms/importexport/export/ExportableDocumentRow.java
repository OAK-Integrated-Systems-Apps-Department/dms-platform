package com.oak.dms.importexport.export;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One document with its current version (current_version_id, else
 * latest_version_id) as bulk export reads it - see
 * {@link ExportableDocumentQuery}. The version columns are null when the
 * document has no such version row.
 *
 * @param uploadedByName the uploader's display name, null if the user is gone
 */
public record ExportableDocumentRow(
        UUID documentId,
        UUID tenantId,
        String title,
        UUID documentTypeId,
        String documentTypeName,
        UUID uploadedById,
        String uploadedByName,
        OffsetDateTime createdAt,
        boolean documentDeleted,
        UUID versionId,
        Integer versionNumber,
        String originalFilename,
        Long sizeBytes,
        String scanStatus,
        boolean versionDeleted
) {
}
