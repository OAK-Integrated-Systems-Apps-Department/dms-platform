package com.oak.dms.reporting.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A run as the administrator sees every run in the organisation: that it happened, for whom, how it ended - never its filters, its
 * file or a way to download it. A report's data belongs to the person it was made for.
 */
public record AdminRunResponse(UUID runId, UUID templateId, UUID scheduleId, UUID requestedBy, String requestedByName, UUID forUserId,
                               String deliveryScope, String trigger, String format, String status, Long rowCount, boolean truncated,
                               String errorCode, OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                               OffsetDateTime expiresAt) {
}
