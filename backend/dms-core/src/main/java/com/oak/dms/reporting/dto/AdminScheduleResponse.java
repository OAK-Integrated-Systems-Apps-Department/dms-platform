package com.oak.dms.reporting.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A schedule as the organisation's administrator sees it: who owns it, when it runs, how it is going. Never who receives it. */
public record AdminScheduleResponse(UUID id, String name, UUID ownerUserId, String ownerName, UUID templateId, String templateName,
                                    String frequency, String timeZone, String format, String status, String pauseReason,
                                    OffsetDateTime nextRunAt, OffsetDateTime lastRunAt, int consecutiveFailures, String lastFailureCode,
                                    long recipientCount) {
}
