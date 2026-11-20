package com.oak.dms.reporting.settings;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.reporting.ReportException;
import com.oak.dms.reporting.ReportProperties;
import com.oak.dms.reporting.dto.ReportSettingsUpdate;
import com.oak.dms.reporting.entity.TenantReportSettings;
import com.oak.dms.reporting.repository.TenantReportSettingsRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.DecimalFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Each organisation's own report settings (CRG-FR-008). The ONLY place that reads {@link ReportProperties}-backed
 * limits for the rest of the module: controllers, the query builder, exporters and the scheduler ask this service.
 * <p>
 * No row = the defaults; a NULL number = the platform value; a saved number can never exceed the platform ceiling
 * (rejected with 422 {@code REPORT_SETTINGS_INVALID} and one message per field). Every real change is audited with old and
 * new values ({@code REPORT_SETTINGS_UPDATED}); saving identical settings is an idempotent no-op and is not audited.
 */
@Service
@RequiredArgsConstructor
public class ReportSettingsService {

    private static final List<String> DELIMITERS = List.of(",", ";", "\t");
    private static final List<String> PAGE_SIZES = List.of("A4", "Letter");
    private static final List<String> ORIENTATIONS = List.of("landscape", "portrait");
    private static final List<String> OVER_LIMIT = List.of("fail", "truncate_and_mark");
    private static final List<String> INTERVALS = List.of("daily", "hourly");
    private static final List<String> MODES = List.of("link", "attachment");
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    /** What the admin screens show beside each input. */
    public record Ceilings(int maxRows, int maxPdfRows, int maxConcurrentRuns, int maxQueuedPerUser, int fileRetentionDays,
                           int maxTemplatesPerUser, int maxSchedulesPerUser, int maxSchedulesPerTenant,
                           int maxRecipientsPerSchedule, int maxAttachmentMb, int catchUpWindowHours,
                           int maxFilters, int maxColumns, int maxMetadataFilters, boolean allowHourlySchedules) {
    }

    private final TenantReportSettingsRepository repository;
    private final ReportSettingsDefaults defaults;
    private final ReportProperties props;
    private final AuditService auditService;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public EffectiveReportSettings forTenant(UUID tenantId) {
        return defaults.resolve(repository.findById(tenantId).orElse(null));
    }

    @Transactional(readOnly = true)
    public EffectiveReportSettings mine() {
        return forTenant(TenantContext.getTenantId());
    }

    /**
     * The guard every report endpoint and job calls first.
     *
     * @throws ReportException 403 {@code REPORTS_DISABLED}
     */
    @Transactional(readOnly = true)
    public EffectiveReportSettings requireEnabled(UUID tenantId) {
        EffectiveReportSettings settings = forTenant(tenantId);
        if (!settings.enabled()) {
            throw ReportException.disabled();
        }
        return settings;
    }

    /** The platform's statement timeout for a preview (not a tenant setting). */
    public java.time.Duration previewTimeout() {
        return props.getPreviewTimeout();
    }

    /** The platform's hard stop for one generation run. */
    public java.time.Duration runTimeout() {
        return props.getRunTimeout();
    }

    public java.util.List<java.nio.file.Path> pdfFontDirectories() {
        return props.getPdfFontDirs().stream().map(java.nio.file.Path::of).toList();
    }

    public java.time.Duration stuckRunGrace() {
        return props.getStuckRunGrace();
    }

    public java.time.Duration runHistoryRetention() {
        return props.getRunHistoryRetention();
    }

    public Ceilings ceilings() {
        return new Ceilings(props.getMaxRows(), props.getMaxPdfRows(), props.getMaxConcurrentPerTenant(),
                props.getMaxQueuedPerUser(), props.getMaxFileRetentionDays(), props.getMaxTemplatesPerUser(),
                props.getMaxSchedulesPerUser(), props.getMaxSchedulesPerTenant(), props.getMaxRecipientsPerSchedule(),
                props.getMaxAttachmentMb(), props.getMaxCatchUpWindowHours(), props.getMaxFilters(),
                props.getMaxColumns(), props.getMaxMetadataFilters(), props.isAllowHourlySchedules());
    }

    /**
     * Replaces the organisation's settings and returns the effective values.
     *
     * @throws ReportException 422 {@code REPORT_SETTINGS_INVALID}; {@code details} maps each offending field to a message
     */
    @Transactional
    public EffectiveReportSettings save(ReportSettingsUpdate wanted) {
        UUID tenantId = TenantContext.getTenantId();
        TenantReportSettings existing = repository.findById(tenantId).orElse(null);
        TenantReportSettings before = existing != null ? existing : TenantReportSettings.builder().tenantId(tenantId).build();
        Map<String, Object> beforeSnapshot = snapshot(before);

        TenantReportSettings after = build(tenantId, wanted);
        Map<String, Object> afterSnapshot = snapshot(after);
        if (beforeSnapshot.equals(afterSnapshot)) {
            return defaults.resolve(existing);
        }

        after.setUpdatedBy(TenantContext.getUserId());
        after.setUpdatedAt(OffsetDateTime.now());
        repository.save(after);

        auditService.event(AuditActionCatalog.REPORT_SETTINGS_UPDATED)
                .objectType(AuditObjectTypes.TENANT).objectId(tenantId)
                .outcome(AuditOutcome.SUCCESS).previousValue(beforeSnapshot).newValue(afterSnapshot).log();
        return defaults.resolve(after);
    }

    /** The settings that WOULD apply if `wanted` were saved - validated like a save, stored nowhere. Feeds the live sample. */
    public EffectiveReportSettings preview(ReportSettingsUpdate wanted) {
        return defaults.resolve(build(TenantContext.getTenantId(), wanted));
    }

    /** Validates {@code wanted} against the enums and the platform ceilings and builds the row; throws with every problem at once. */
    TenantReportSettings build(UUID tenantId, ReportSettingsUpdate w) {
        Map<String, String> errors = new LinkedHashMap<>();
        TenantReportSettings s = TenantReportSettings.builder().tenantId(tenantId).build();

        if (w.enabled() != null) s.setEnabled(w.enabled());
        if (w.schedulingEnabled() != null) s.setSchedulingEnabled(w.schedulingEnabled());

        if (w.allowedFormats() != null) {
            List<String> formats = normalise(w.allowedFormats());
            if (formats.isEmpty() || !ReportSettingsDefaults.ALL_FORMATS.containsAll(formats)) {
                errors.put("allowedFormats", "Choose at least one of pdf, xlsx and csv.");
            } else {
                s.setAllowedFormats(formats);
            }
        }
        if (w.defaultFormat() != null) {
            String format = w.defaultFormat().trim().toLowerCase(Locale.ROOT);
            if (!ReportSettingsDefaults.ALL_FORMATS.contains(format)) {
                errors.put("defaultFormat", "The default format must be pdf, xlsx or csv.");
            } else {
                s.setDefaultFormat(format);
            }
        } else if (!s.getAllowedFormats().contains(s.getDefaultFormat())) {
            // the code default (xlsx) is not offered: fall back to the first allowed format rather than reject silently
            s.setDefaultFormat(s.getAllowedFormats().isEmpty() ? "xlsx" : s.getAllowedFormats().get(0));
        }
        if (!errors.containsKey("defaultFormat") && !errors.containsKey("allowedFormats")
                && !s.getAllowedFormats().contains(s.getDefaultFormat())) {
            errors.put("defaultFormat", "The default format must be one of the allowed formats.");
        }

        if (w.csvDelimiter() != null) {
            if (!DELIMITERS.contains(w.csvDelimiter())) {
                errors.put("csvDelimiter", "The CSV delimiter must be a comma, a semicolon or a tab.");
            } else {
                s.setCsvDelimiter(w.csvDelimiter());
            }
        }
        if (w.csvIncludeBom() != null) s.setCsvIncludeBom(w.csvIncludeBom());

        s.setDateFormat(blankToNull(w.dateFormat()));
        if (s.getDateFormat() != null) {
            try {
                DateTimeFormatter.ofPattern(s.getDateFormat());
            } catch (IllegalArgumentException e) {
                errors.put("dateFormat", "That is not a valid date pattern (for example yyyy-MM-dd or dd/MM/yyyy).");
            }
        }
        s.setNumberFormat(blankToNull(w.numberFormat()));
        if (s.getNumberFormat() != null) {
            try {
                new DecimalFormat(s.getNumberFormat());
            } catch (IllegalArgumentException e) {
                errors.put("numberFormat", "That is not a valid number pattern (for example #,##0.00).");
            }
        }
        s.setDefaultTimeZone(blankToNull(w.defaultTimeZone()));
        if (s.getDefaultTimeZone() != null && !ZoneId.getAvailableZoneIds().contains(s.getDefaultTimeZone())) {
            errors.put("defaultTimeZone", "Use an IANA time zone such as Europe/London or Asia/Colombo.");
        }
        s.setDefaultLocale(blankToNull(w.defaultLocale()));
        if (s.getDefaultLocale() != null && Locale.forLanguageTag(s.getDefaultLocale()).getLanguage().isEmpty()) {
            errors.put("defaultLocale", "Use a language tag such as en-GB or si-LK.");
        }

        s.setHeaderText(text(w.headerText(), 200, "headerText", errors));
        s.setFooterText(text(w.footerText(), 200, "footerText", errors));
        s.setConfidentialityNotice(text(w.confidentialityNotice(), 500, "confidentialityNotice", errors));
        if (w.showLogo() != null) s.setShowLogo(w.showLogo());
        if (w.showFilterSummary() != null) s.setShowFilterSummary(w.showFilterSummary());
        if (w.showGeneratedBy() != null) s.setShowGeneratedBy(w.showGeneratedBy());

        if (w.pdfPageSize() != null) {
            if (!PAGE_SIZES.contains(w.pdfPageSize())) {
                errors.put("pdfPageSize", "The page size must be A4 or Letter.");
            } else {
                s.setPdfPageSize(w.pdfPageSize());
            }
        }
        if (w.pdfOrientation() != null) {
            if (!ORIENTATIONS.contains(w.pdfOrientation())) {
                errors.put("pdfOrientation", "The orientation must be landscape or portrait.");
            } else {
                s.setPdfOrientation(w.pdfOrientation());
            }
        }

        s.setMaxRows(limit(w.maxRows(), props.getMaxRows(), "maxRows", errors));
        s.setMaxPdfRows(limit(w.maxPdfRows(), props.getMaxPdfRows(), "maxPdfRows", errors));
        if (w.overLimitBehaviour() != null) {
            if (!OVER_LIMIT.contains(w.overLimitBehaviour())) {
                errors.put("overLimitBehaviour", "Choose fail, or truncate_and_mark.");
            } else {
                s.setOverLimitBehaviour(w.overLimitBehaviour());
            }
        }
        s.setMaxConcurrentRuns(limit(w.maxConcurrentRuns(), props.getMaxConcurrentPerTenant(), "maxConcurrentRuns", errors));
        s.setFileRetentionDays(limit(w.fileRetentionDays(), props.getMaxFileRetentionDays(), "fileRetentionDays", errors));
        s.setMaxTemplatesPerUser(limit(w.maxTemplatesPerUser(), props.getMaxTemplatesPerUser(), "maxTemplatesPerUser", errors));
        s.setMaxSchedulesPerUser(limit(w.maxSchedulesPerUser(), props.getMaxSchedulesPerUser(), "maxSchedulesPerUser", errors));
        s.setMaxSchedulesPerTenant(limit(w.maxSchedulesPerTenant(), props.getMaxSchedulesPerTenant(), "maxSchedulesPerTenant", errors));
        s.setMaxRecipientsPerSchedule(limit(w.maxRecipientsPerSchedule(), props.getMaxRecipientsPerSchedule(), "maxRecipientsPerSchedule", errors));
        s.setMaxAttachmentMb(limit(w.maxAttachmentMb(), props.getMaxAttachmentMb(), "maxAttachmentMb", errors));
        s.setPauseAfterConsecutiveFailures(limit(w.pauseAfterConsecutiveFailures(), 1000, "pauseAfterConsecutiveFailures", errors));
        s.setCatchUpWindowHours(limit(w.catchUpWindowHours(), props.getMaxCatchUpWindowHours(), "catchUpWindowHours", errors));

        if (w.minScheduleInterval() != null) {
            if (!INTERVALS.contains(w.minScheduleInterval())) {
                errors.put("minScheduleInterval", "Choose daily or hourly.");
            } else if ("hourly".equals(w.minScheduleInterval()) && !props.isAllowHourlySchedules()) {
                errors.put("minScheduleInterval", "Hourly schedules are not enabled on this platform.");
            } else {
                s.setMinScheduleInterval(w.minScheduleInterval());
            }
        }

        if (w.allowedDeliveryChannels() != null) {
            List<String> channels = normalise(w.allowedDeliveryChannels());
            if (!ReportSettingsDefaults.ALL_CHANNELS.containsAll(channels)) {
                errors.put("allowedDeliveryChannels", "Delivery channels can be in_app and email.");
            } else {
                s.setAllowedDeliveryChannels(channels);
            }
        }
        if (w.allowAttachments() != null) s.setAllowAttachments(w.allowAttachments());
        if (w.deliveryModeDefault() != null) {
            if (!MODES.contains(w.deliveryModeDefault())) {
                errors.put("deliveryModeDefault", "Choose link or attachment.");
            } else {
                s.setDeliveryModeDefault(w.deliveryModeDefault());
            }
        }
        if ("attachment".equals(s.getDeliveryModeDefault()) && !s.isAllowAttachments()
                && !errors.containsKey("deliveryModeDefault")) {
            errors.put("deliveryModeDefault", "Attachments must be allowed before they can be the default.");
        }

        if (w.allowExternalRecipients() != null) s.setAllowExternalRecipients(w.allowExternalRecipients());
        if (w.externalDomainAllowlist() != null) {
            List<String> domains = normalise(w.externalDomainAllowlist());
            for (String domain : domains) {
                if (!DOMAIN.matcher(domain).matches()) {
                    errors.put("externalDomainAllowlist", "\"" + domain + "\" is not a valid domain (for example example.com).");
                    break;
                }
            }
            if (!errors.containsKey("externalDomainAllowlist")) s.setExternalDomainAllowlist(domains);
        }
        if (w.failureNotifyAdmins() != null) s.setFailureNotifyAdmins(w.failureNotifyAdmins());
        if (w.failureAdminUserIds() != null) {
            List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(w.failureAdminUserIds()));
            if (!ids.isEmpty() && userRepository.findAllByUserIdInAndTenantIdAndDeletedFalse(ids, tenantId).size() != ids.size()) {
                errors.put("failureAdminUserIds", "One or more of the chosen people are not users of your organisation.");
            } else {
                s.setFailureAdminUserIds(ids);
            }
        }
        if (w.skipEmptyScheduledReports() != null) s.setSkipEmptyScheduledReports(w.skipEmptyScheduledReports());
        if (w.includeInactiveByDefault() != null) s.setIncludeInactiveByDefault(w.includeInactiveByDefault());
        if (w.defaultColumns() != null) {
            if (w.defaultColumns().size() > props.getMaxColumns()) {
                errors.put("defaultColumns", "At most " + props.getMaxColumns() + " columns.");
            } else {
                s.setDefaultColumns(new ArrayList<>(new LinkedHashSet<>(w.defaultColumns())));
            }
        }
        s.setDefaultSort(w.defaultSort() == null || w.defaultSort().isEmpty() ? null : List.copyOf(w.defaultSort()));
        if (s.getDefaultSort() != null && s.getDefaultSort().size() > 3) {
            errors.put("defaultSort", "At most 3 sort keys.");
        }

        if (!errors.isEmpty()) {
            throw ReportException.invalid("REPORT_SETTINGS_INVALID", "Some report settings are not valid.", errors);
        }
        return s;
    }

    private static Integer limit(Integer value, int ceiling, String field, Map<String, String> errors) {
        if (value == null) {
            return null;
        }
        if (value < 1) {
            errors.put(field, "Must be at least 1.");
            return null;
        }
        if (value > ceiling) {
            errors.put(field, "The platform allows at most " + ceiling + ".");
            return null;
        }
        return value;
    }

    private static String text(String value, int max, String field, Map<String, String> errors) {
        String v = blankToNull(value);
        if (v != null && v.length() > max) {
            errors.put(field, "At most " + max + " characters.");
            return null;
        }
        return v;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> normalise(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String v : values) {
            if (v == null) continue;
            String n = v.trim().toLowerCase(Locale.ROOT);
            if (!n.isEmpty() && !result.contains(n)) result.add(n);
        }
        return result;
    }

    /** Every stored column, for the audit trail and the no-op comparison. Nothing here is sensitive. */
    static Map<String, Object> snapshot(TenantReportSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.isEnabled());
        m.put("schedulingEnabled", s.isSchedulingEnabled());
        m.put("allowedFormats", s.getAllowedFormats());
        m.put("defaultFormat", s.getDefaultFormat());
        m.put("csvDelimiter", s.getCsvDelimiter());
        m.put("csvIncludeBom", s.isCsvIncludeBom());
        m.put("dateFormat", s.getDateFormat());
        m.put("numberFormat", s.getNumberFormat());
        m.put("defaultTimeZone", s.getDefaultTimeZone());
        m.put("defaultLocale", s.getDefaultLocale());
        m.put("headerText", s.getHeaderText());
        m.put("footerText", s.getFooterText());
        m.put("confidentialityNotice", s.getConfidentialityNotice());
        m.put("showLogo", s.isShowLogo());
        m.put("showFilterSummary", s.isShowFilterSummary());
        m.put("showGeneratedBy", s.isShowGeneratedBy());
        m.put("pdfPageSize", s.getPdfPageSize());
        m.put("pdfOrientation", s.getPdfOrientation());
        m.put("maxRows", s.getMaxRows());
        m.put("maxPdfRows", s.getMaxPdfRows());
        m.put("overLimitBehaviour", s.getOverLimitBehaviour());
        m.put("maxConcurrentRuns", s.getMaxConcurrentRuns());
        m.put("fileRetentionDays", s.getFileRetentionDays());
        m.put("maxTemplatesPerUser", s.getMaxTemplatesPerUser());
        m.put("maxSchedulesPerUser", s.getMaxSchedulesPerUser());
        m.put("maxSchedulesPerTenant", s.getMaxSchedulesPerTenant());
        m.put("minScheduleInterval", s.getMinScheduleInterval());
        m.put("maxRecipientsPerSchedule", s.getMaxRecipientsPerSchedule());
        m.put("allowedDeliveryChannels", s.getAllowedDeliveryChannels());
        m.put("deliveryModeDefault", s.getDeliveryModeDefault());
        m.put("allowAttachments", s.isAllowAttachments());
        m.put("maxAttachmentMb", s.getMaxAttachmentMb());
        m.put("allowExternalRecipients", s.isAllowExternalRecipients());
        m.put("externalDomainAllowlist", s.getExternalDomainAllowlist());
        m.put("failureNotifyAdmins", s.isFailureNotifyAdmins());
        m.put("failureAdminUserIds", s.getFailureAdminUserIds());
        m.put("pauseAfterConsecutiveFailures", s.getPauseAfterConsecutiveFailures());
        m.put("catchUpWindowHours", s.getCatchUpWindowHours());
        m.put("skipEmptyScheduledReports", s.isSkipEmptyScheduledReports());
        m.put("includeInactiveByDefault", s.isIncludeInactiveByDefault());
        m.put("defaultColumns", s.getDefaultColumns());
        m.put("defaultSort", s.getDefaultSort());
        return m;
    }
}
