package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * canOpen is the viewer's own docm.documents.view (decision 18) and their
 * access to this document: without it they see the title and note but get no
 * link - there is no per-document grant to bypass. The pinned version's file
 * fields ({@code fileName}/{@code mimeType}/{@code sizeBytes}) are populated
 * only when canOpen, so the shared viewer can render it; null otherwise.
 */
public record SupportingDocumentView(UUID supportingDocumentId, UUID instanceId, UUID documentId, String title,
                                     UUID versionId, Integer versionNumber, String source, String note, UserRef addedBy,
                                     OffsetDateTime addedAt, UUID infoRequestId, boolean canOpen,
                                     String fileName, String mimeType, Long sizeBytes) {
}
