package com.oak.dms.reporting.settings;

import com.oak.dms.reporting.ReportProperties;
import com.oak.dms.reporting.entity.TenantReportSettings;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Merges the three layers that decide a report setting: the tenant's row, the platform ceilings
 * ({@code dms.reports}) and the code defaults. The rule is {@code min(tenantValue, platformCeiling)}, and a NULL tenant
 * value means the platform default (itself capped at the ceiling). Pure and side-effect free, so it is unit-tested
 * without Spring.
 */
@Component
public class ReportSettingsDefaults {

    public static final List<String> ALL_FORMATS = List.of("pdf", "xlsx", "csv");
    public static final List<String> ALL_CHANNELS = List.of("in_app", "email");

    private final ReportProperties props;

    public ReportSettingsDefaults(ReportProperties props) {
        this.props = props;
    }

    /** @param row the organisation's row, or null for "no row = the defaults" */
    public EffectiveReportSettings resolve(TenantReportSettings row) {
        TenantReportSettings s = row != null ? row : TenantReportSettings.builder().build();
        boolean enabled = props.isEnabled() && s.isEnabled();
        return new EffectiveReportSettings(
                enabled,
                enabled && s.isSchedulingEnabled(),
                List.copyOf(s.getAllowedFormats()),
                s.getDefaultFormat(),
                s.getCsvDelimiter(),
                s.isCsvIncludeBom(),
                s.getDateFormat(),
                s.getNumberFormat(),
                s.getDefaultTimeZone(),
                s.getDefaultLocale(),
                s.getHeaderText(),
                s.getFooterText(),
                s.getConfidentialityNotice(),
                s.isShowLogo(),
                s.isShowFilterSummary(),
                s.isShowGeneratedBy(),
                s.getPdfPageSize(),
                s.getPdfOrientation(),
                cap(s.getMaxRows(), props.getMaxRows(), props.getMaxRows()),
                cap(s.getMaxPdfRows(), props.getMaxPdfRows(), props.getMaxPdfRows()),
                s.getOverLimitBehaviour(),
                cap(s.getMaxConcurrentRuns(), props.getMaxConcurrentPerTenant(), props.getDefaultConcurrentPerTenant()),
                props.getMaxQueuedPerUser(),
                cap(s.getFileRetentionDays(), props.getMaxFileRetentionDays(), props.getDefaultFileRetentionDays()),
                cap(s.getMaxTemplatesPerUser(), props.getMaxTemplatesPerUser(), props.getMaxTemplatesPerUser()),
                cap(s.getMaxSchedulesPerUser(), props.getMaxSchedulesPerUser(), props.getMaxSchedulesPerUser()),
                cap(s.getMaxSchedulesPerTenant(), props.getMaxSchedulesPerTenant(), props.getMaxSchedulesPerTenant()),
                // hourly is only honoured while the platform flag permits it; otherwise daily is the floor
                "hourly".equals(s.getMinScheduleInterval()) && props.isAllowHourlySchedules() ? "hourly" : "daily",
                cap(s.getMaxRecipientsPerSchedule(), props.getMaxRecipientsPerSchedule(), props.getDefaultRecipientsPerSchedule()),
                List.copyOf(s.getAllowedDeliveryChannels()),
                s.getDeliveryModeDefault(),
                s.isAllowAttachments(),
                cap(s.getMaxAttachmentMb(), props.getMaxAttachmentMb(), props.getDefaultAttachmentMb()),
                s.isAllowExternalRecipients(),
                List.copyOf(s.getExternalDomainAllowlist()),
                s.isFailureNotifyAdmins(),
                List.copyOf(s.getFailureAdminUserIds()),
                cap(s.getPauseAfterConsecutiveFailures(), Integer.MAX_VALUE, props.getDefaultPauseAfterFailures()),
                cap(s.getCatchUpWindowHours(), props.getMaxCatchUpWindowHours(), props.getDefaultCatchUpWindowHours()),
                s.isSkipEmptyScheduledReports(),
                s.isIncludeInactiveByDefault(),
                List.copyOf(s.getDefaultColumns()),
                s.getDefaultSort() == null ? null : List.copyOf(s.getDefaultSort()));
    }

    /** {@code min(tenantValue, ceiling)}; a null tenant value takes the platform default, still capped at the ceiling. */
    static int cap(Integer tenantValue, int ceiling, int platformDefault) {
        return Math.min(tenantValue != null ? tenantValue : platformDefault, ceiling);
    }
}
