package com.oak.dms.reporting.datasource;

import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.reporting.settings.EffectiveReportSettings;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Who a report is for and under which rules. The tenant and user come from the authenticated request (or, for a scheduled run,
 * from the schedule) - never from a request body.
 *
 * @param access   the access view of the person the report is FOR: {@code forCaller()} interactively, {@code forUser(...)} when scheduled
 * @param zone     the zone relative dates are resolved in and times are printed in
 * @param now      the moment the report is evaluated at - fixed per run so every relative date agrees
 */
public record ReportContext(UUID tenantId, UUID userId, DocumentAccessView access, EffectiveReportSettings settings,
                            ZoneId zone, Instant now) {

    /** The definition's own zone if it names one, else the organisation's default. */
    public static ZoneId zoneFor(String requested, EffectiveReportSettings settings) {
        String name = requested != null && !requested.isBlank() ? requested.strip() : settings.defaultTimeZone();
        try {
            return ZoneId.of(name == null || name.isBlank() ? "UTC" : name);
        } catch (java.time.DateTimeException e) {
            String message = "Use an IANA time zone such as Europe/London or Asia/Colombo.";
            throw com.oak.dms.reporting.ReportException.invalid("REPORT_TIMEZONE_INVALID", message,
                    java.util.Map.of("timeZone", message));
        }
    }
}
