package com.oak.dms.workflow.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** An information request with its response (Phase 2 S7). documentTitle helps the "Requests for me" queue. */
public record InfoRequestView(
        UUID infoRequestId,
        UUID instanceId,
        UUID documentId,
        String documentTitle,
        UUID taskId,
        UUID stageInstanceId,
        Integer stageOrder,
        UserRef requestedBy,
        UserRef requestedFrom,
        String message,
        String status,
        String responseMessage,
        String declineReason,
        String cancelReason,
        OffsetDateTime dueAt,
        OffsetDateTime requestedAt,
        OffsetDateTime respondedAt,
        UserRef respondedBy,
        List<SupportingDocumentView> supportingDocuments
) {
}
