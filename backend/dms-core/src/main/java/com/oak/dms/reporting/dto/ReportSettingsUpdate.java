package com.oak.dms.reporting.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Body of {@code PUT /api/v1/reports/admin/settings}: the organisation's stored settings, replaced as a whole. A null
 * integer means "use the platform value"; every number is validated against the platform ceiling. Null lists and
 * booleans are treated as "not given" and keep the code default.
 */
public record ReportSettingsUpdate(
        Boolean enabled,
        Boolean schedulingEnabled,
        List<String> allowedFormats,
        String defaultFormat,
        String csvDelimiter,
        Boolean csvIncludeBom,
        String dateFormat,
        String numberFormat,
        String defaultTimeZone,
        String defaultLocale,
        String headerText,
        String footerText,
        String confidentialityNotice,
        Boolean showLogo,
        Boolean showFilterSummary,
        Boolean showGeneratedBy,
        String pdfPageSize,
        String pdfOrientation,
        Integer maxRows,
        Integer maxPdfRows,
        String overLimitBehaviour,
        Integer maxConcurrentRuns,
        Integer fileRetentionDays,
        Integer maxTemplatesPerUser,
        Integer maxSchedulesPerUser,
        Integer maxSchedulesPerTenant,
        String minScheduleInterval,
        Integer maxRecipientsPerSchedule,
        List<String> allowedDeliveryChannels,
        String deliveryModeDefault,
        Boolean allowAttachments,
        Integer maxAttachmentMb,
        Boolean allowExternalRecipients,
        List<String> externalDomainAllowlist,
        Boolean failureNotifyAdmins,
        List<UUID> failureAdminUserIds,
        Integer pauseAfterConsecutiveFailures,
        Integer catchUpWindowHours,
        Boolean skipEmptyScheduledReports,
        Boolean includeInactiveByDefault,
        List<String> defaultColumns,
        List<Map<String, Object>> defaultSort) {
}
