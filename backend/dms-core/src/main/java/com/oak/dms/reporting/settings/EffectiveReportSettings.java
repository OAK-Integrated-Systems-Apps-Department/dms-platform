package com.oak.dms.reporting.settings;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An organisation's report settings AFTER the tenant row, the platform ceilings and the code defaults have been merged
 * (see {@link ReportSettingsDefaults}). Nothing here is null except the optional text and format fields; every limit is a
 * concrete number already capped at the platform ceiling.
 *
 * @param enabled           false when the platform switch OR the organisation's own switch is off
 * @param schedulingEnabled false when reporting is off, or scheduling is off
 */
public record EffectiveReportSettings(
        boolean enabled,
        boolean schedulingEnabled,
        List<String> allowedFormats,
        String defaultFormat,
        String csvDelimiter,
        boolean csvIncludeBom,
        String dateFormat,
        String numberFormat,
        String defaultTimeZone,
        String defaultLocale,
        String headerText,
        String footerText,
        String confidentialityNotice,
        boolean showLogo,
        boolean showFilterSummary,
        boolean showGeneratedBy,
        String pdfPageSize,
        String pdfOrientation,
        int maxRows,
        int maxPdfRows,
        String overLimitBehaviour,
        int maxConcurrentRuns,
        int maxQueuedPerUser,
        int fileRetentionDays,
        int maxTemplatesPerUser,
        int maxSchedulesPerUser,
        int maxSchedulesPerTenant,
        String minScheduleInterval,
        int maxRecipientsPerSchedule,
        List<String> allowedDeliveryChannels,
        String deliveryModeDefault,
        boolean allowAttachments,
        int maxAttachmentMb,
        boolean allowExternalRecipients,
        List<String> externalDomainAllowlist,
        boolean failureNotifyAdmins,
        List<UUID> failureAdminUserIds,
        int pauseAfterConsecutiveFailures,
        int catchUpWindowHours,
        boolean skipEmptyScheduledReports,
        boolean includeInactiveByDefault,
        List<String> defaultColumns,
        List<Map<String, Object>> defaultSort) {

    public boolean allows(String format) {
        return format != null && allowedFormats.contains(format);
    }

    /** The row limit that applies to a format: PDF has its own, lower, limit. */
    public int rowLimitFor(String format) {
        return "pdf".equals(format) ? maxPdfRows : maxRows;
    }

    public boolean truncateOnOverLimit() {
        return "truncate_and_mark".equals(overLimitBehaviour);
    }
}
