package com.oak.dms.reporting.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A schedule, with its recipients. External addresses are shown only to the schedule's owner. */
public record ScheduleResponse(
        UUID id,
        UUID templateId,
        String templateName,
        UUID ownerUserId,
        String name,
        String frequency,
        List<Integer> daysOfWeek,
        Integer dayOfMonth,
        LocalTime timeOfDay,
        String timeZone,
        LocalDate startDate,
        LocalDate endDate,
        String format,
        List<String> deliveryChannels,
        String deliveryMode,
        String status,
        String pauseReason,
        OffsetDateTime nextRunAt,
        OffsetDateTime lastRunAt,
        int consecutiveFailures,
        String lastFailureCode,
        List<ScheduleRequest.Recipient> recipients,
        OffsetDateTime createdAt) {
}
