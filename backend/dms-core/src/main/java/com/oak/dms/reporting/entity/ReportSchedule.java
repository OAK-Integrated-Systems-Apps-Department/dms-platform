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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A template run repeatedly (V138 {@code report_schedules}). {@code nextRunAt} is worked out by {@code NextRunCalculator} in
 * {@code timeZone}; the scheduler advances it in the same transaction that creates the run.
 */
@Entity
@Table(name = "report_schedules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportSchedule {

    public static final String ACTIVE = "active";
    public static final String PAUSED = "paused";
    public static final String PAUSED_FAILED = "paused_failed";
    public static final String CANCELLED = "cancelled";
    public static final String ENDED = "ended";

    @Id
    @Column(name = "schedule_id", updatable = false, nullable = false)
    @Builder.Default
    private UUID scheduleId = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "frequency", nullable = false)
    private String frequency;

    /** ISO 1..7, Monday = 1. */
    @Column(name = "days_of_week", nullable = false, columnDefinition = "smallint[]")
    @Builder.Default
    private List<Short> daysOfWeek = new ArrayList<>();

    /** 1..31, or -1 for the last day. */
    @Column(name = "day_of_month")
    private Short dayOfMonth;

    @Column(name = "time_of_day", nullable = false)
    private LocalTime timeOfDay;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "format", nullable = false)
    private String format;

    @Column(name = "delivery_channels", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private List<String> deliveryChannels = new ArrayList<>();

    @Column(name = "delivery_mode", nullable = false)
    @Builder.Default
    private String deliveryMode = "link";

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = ACTIVE;

    @Column(name = "pause_reason")
    private String pauseReason;

    @Column(name = "next_run_at")
    private OffsetDateTime nextRunAt;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "last_failure_slot")
    private OffsetDateTime lastFailureSlot;

    @Column(name = "last_failure_code")
    private String lastFailureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public boolean isLive() {
        return ACTIVE.equals(status) || PAUSED.equals(status) || PAUSED_FAILED.equals(status);
    }
}
