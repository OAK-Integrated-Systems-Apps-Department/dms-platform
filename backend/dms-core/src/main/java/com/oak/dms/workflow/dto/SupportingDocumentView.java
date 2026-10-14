package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * canOpen is the viewer's own docm.documents.view (decision 18): without it
 * they see the title and note but get no link - there is no per-document
 * grant to bypass.
 */
public record SupportingDocumentView(UUID supportingDocumentId, UUID documentId, String title, UUID versionId,
                                     Integer versionNumber, String source, String note, UserRef addedBy,
                                     OffsetDateTime addedAt, UUID infoRequestId, boolean canOpen) {
}
