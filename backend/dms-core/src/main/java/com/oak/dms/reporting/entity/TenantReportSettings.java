package com.oak.dms.reporting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One organisation's report settings (V138). No row means the defaults, and a NULL integer means "the platform value" -
 * both resolved in {@link com.oak.dms.reporting.settings.ReportSettingsService}. The field defaults below mirror the
 * column defaults so a freshly built (unsaved) row is exactly "the defaults".
 */
@Entity
@Table(name = "tenant_report_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantReportSettings {

    @Id
    @Column(name = "tenant_id", updatable = false, nullable = false)
    private UUID tenantId;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "scheduling_enabled", nullable = false)
    @Builder.Default
    private boolean schedulingEnabled = true;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_formats", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private List<String> allowedFormats = new ArrayList<>(List.of("pdf", "xlsx", "csv"));

    @Column(name = "default_format", nullable = false)
    @Builder.Default
    private String defaultFormat = "xlsx";

    @Column(name = "csv_delimiter", nullable = false)
    @Builder.Default
    private String csvDelimiter = ",";

    @Column(name = "csv_include_bom", nullable = false)
    @Builder.Default
    private boolean csvIncludeBom = true;

    @Column(name = "date_format")
    private String dateFormat;

    @Column(name = "number_format")
    private String numberFormat;

    @Column(name = "default_time_zone")
    private String defaultTimeZone;

    @Column(name = "default_locale")
    private String defaultLocale;

    @Column(name = "header_text")
    private String headerText;

    @Column(name = "footer_text")
    private String footerText;

    @Column(name = "confidentiality_notice")
    private String confidentialityNotice;

    @Column(name = "show_logo", nullable = false)
    @Builder.Default
    private boolean showLogo = true;

    @Column(name = "show_filter_summary", nullable = false)
    @Builder.Default
    private boolean showFilterSummary = true;

    @Column(name = "show_generated_by", nullable = false)
    @Builder.Default
    private boolean showGeneratedBy = true;

    @Column(name = "pdf_page_size", nullable = false)
    @Builder.Default
    private String pdfPageSize = "A4";

    @Column(name = "pdf_orientation", nullable = false)
    @Builder.Default
    private String pdfOrientation = "landscape";

    @Column(name = "max_rows")
    private Integer maxRows;

    @Column(name = "max_pdf_rows")
    private Integer maxPdfRows;

    @Column(name = "over_limit_behaviour", nullable = false)
    @Builder.Default
    private String overLimitBehaviour = "fail";

    @Column(name = "max_concurrent_runs")
    private Integer maxConcurrentRuns;

    @Column(name = "file_retention_days")
    private Integer fileRetentionDays;

    @Column(name = "max_templates_per_user")
    private Integer maxTemplatesPerUser;

    @Column(name = "max_schedules_per_user")
    private Integer maxSchedulesPerUser;

    @Column(name = "max_schedules_per_tenant")
    private Integer maxSchedulesPerTenant;

    @Column(name = "min_schedule_interval", nullable = false)
    @Builder.Default
    private String minScheduleInterval = "daily";

    @Column(name = "max_recipients_per_schedule")
    private Integer maxRecipientsPerSchedule;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_delivery_channels", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private List<String> allowedDeliveryChannels = new ArrayList<>(List.of("in_app", "email"));

    @Column(name = "delivery_mode_default", nullable = false)
    @Builder.Default
    private String deliveryModeDefault = "link";

    @Column(name = "allow_attachments", nullable = false)
    private boolean allowAttachments;

    @Column(name = "max_attachment_mb")
    private Integer maxAttachmentMb;

    @Column(name = "allow_external_recipients", nullable = false)
    private boolean allowExternalRecipients;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "external_domain_allowlist", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private List<String> externalDomainAllowlist = new ArrayList<>();

    @Column(name = "failure_notify_admins", nullable = false)
    @Builder.Default
    private boolean failureNotifyAdmins = true;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "failure_admin_user_ids", nullable = false, columnDefinition = "uuid[]")
    @Builder.Default
    private List<UUID> failureAdminUserIds = new ArrayList<>();

    @Column(name = "pause_after_consecutive_failures")
    private Integer pauseAfterConsecutiveFailures;

    @Column(name = "catch_up_window_hours")
    private Integer catchUpWindowHours;

    @Column(name = "skip_empty_scheduled_reports", nullable = false)
    private boolean skipEmptyScheduledReports;

    @Column(name = "include_inactive_by_default", nullable = false)
    private boolean includeInactiveByDefault;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "default_columns", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private List<String> defaultColumns = new ArrayList<>();

    /** A list of {@code {"field": "doc.title", "direction": "asc"}} objects; null = no tenant default. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_sort", columnDefinition = "jsonb")
    private List<Map<String, Object>> defaultSort;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
