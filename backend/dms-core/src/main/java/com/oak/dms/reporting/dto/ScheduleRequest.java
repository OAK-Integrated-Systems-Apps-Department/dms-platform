package com.oak.dms.reporting.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Creates or replaces a schedule: a template, when it runs, in what format, and who gets it.
 *
 * @param frequency       hourly (only when the organisation allows it; fires every hour at the minute of timeOfDay) | daily | weekly | monthly
 * @param daysOfWeek      weekly: ISO days, Monday = 1 .. Sunday = 7
 * @param dayOfMonth      monthly: 1..31, or -1 for the last day (a day a short month lacks runs on that month's last day)
 * @param timeOfDay       wall-clock time in {@code timeZone}, e.g. {@code 08:00}
 * @param timeZone        IANA zone, e.g. {@code Asia/Colombo}
 * @param deliveryChannels in_app and/or email
 * @param deliveryMode    link (default) | attachment
 * @param recipients      empty = the owner. Internal recipients get the report under THEIR OWN access; external addresses get it
 *                        under the OWNER's access, as an attachment.
 */
public record ScheduleRequest(
        UUID templateId,
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
        List<Recipient> recipients) {

    /** @param type user | role | department | email; @param ref the id, or the address for email */
    public record Recipient(String type, String ref) {
    }
}
