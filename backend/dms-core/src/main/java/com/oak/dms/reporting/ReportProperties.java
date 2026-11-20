package com.oak.dms.reporting;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Binds {@code dms.reports.*} - the PLATFORM ceilings and defaults for Custom Report Generation (5.3).
 * <p>
 * A tenant may set lower values in its own {@code tenant_report_settings} row, never higher; that merge lives in
 * {@link com.oak.dms.reporting.settings.ReportSettingsService} and nowhere else - controllers and exporters never read
 * this class directly. Env names are {@code DMS_REPORTS_*} (see .env.example).
 */
@Component
@ConfigurationProperties(prefix = "dms.reports")
@Getter
@Setter
public class ReportProperties {

    /** Global off switch: while false no organisation can use reports (403 REPORTS_DISABLED). */
    private boolean enabled = true;

    /** Ceiling (and default) for rows in one Excel/CSV report. */
    private int maxRows = 200_000;
    /** Ceiling (and default) for rows in one PDF report. */
    private int maxPdfRows = 5_000;

    /** Filters on custom metadata fields hit JSON, so they are capped per definition. */
    private int maxMetadataFilters = 5;
    private int maxFilters = 25;
    private int maxColumns = 40;

    /** Statement timeout of the synchronous preview. */
    private Duration previewTimeout = Duration.ofSeconds(10);
    /** Hard stop of a generation run. */
    private Duration runTimeout = Duration.ofMinutes(5);

    /** Ceiling of a tenant's concurrent runs, and the value used when the tenant sets none. */
    private int maxConcurrentPerTenant = 10;
    private int defaultConcurrentPerTenant = 3;
    /** Queued + running runs one user may have; the next interactive request gets 429 REPORT_RATE_LIMITED. */
    private int maxQueuedPerUser = 5;

    /** Where PDF reports look for font files (the first existing directories are searched, recursively). */
    private java.util.List<String> pdfFontDirs = java.util.List.of("/usr/share/fonts", "C:/Windows/Fonts");
    /** How often queued runs that could not start at once (their organisation was at its limit) are looked at again. */
    private Duration queuePoll = Duration.ofSeconds(5);
    /** How often finished files are expired, stuck runs failed and old run history deleted. */
    private Duration sweepInterval = Duration.ofMinutes(10);
    /** A run still 'running' this long after its run timeout belonged to a process that died. */
    private Duration stuckRunGrace = Duration.ofMinutes(2);

    private int maxFileRetentionDays = 90;
    private int defaultFileRetentionDays = 7;
    /** How long run METADATA (not files) is kept. */
    private Duration runHistoryRetention = Duration.ofDays(180);

    private int maxAttachmentMb = 25;
    private int defaultAttachmentMb = 10;

    private int maxTemplatesPerUser = 200;
    private int maxSchedulesPerUser = 50;
    private int maxSchedulesPerTenant = 1000;

    private int maxRecipientsPerSchedule = 100;
    private int defaultRecipientsPerSchedule = 25;

    private int defaultPauseAfterFailures = 5;
    private int maxCatchUpWindowHours = 168;
    private int defaultCatchUpWindowHours = 24;

    /** Whether a tenant may choose an hourly minimum schedule interval; otherwise daily is the floor. */
    private boolean allowHourlySchedules = false;

    /** ReportScheduleJob on/off and poll interval. */
    private boolean scheduleEnabled = true;
    private Duration schedulePoll = Duration.ofMinutes(1);
    /** How often deliveries waiting for a retry (1, 5, 15 minutes after a failed attempt) are looked at. */
    private Duration deliveryPoll = Duration.ofSeconds(30);
}
