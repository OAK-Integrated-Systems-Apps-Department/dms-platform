package com.oak.dms.reporting.entity;

import com.oak.dms.reporting.run.ReportRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.Map;
import java.util.UUID;

/**
 * One generation of a report (V138 {@code report_runs}). {@link #definitionSnapshot} holds exactly what was executed - the
 * definition, the time zone and the template it came from - so a failed run can be retried from the same snapshot and an old run
 * stays explainable after its template changed. Status changes go through {@code ReportRunStateMachine}.
 */
@Entity
@Table(name = "report_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportRun {

    public static final String TRIGGER_MANUAL = "manual";
    public static final String TRIGGER_SCHEDULED = "scheduled";
    public static final String TRIGGER_RETRY = "retry";

    public static final String SCOPE_OWNER = "owner";
    public static final String SCOPE_RECIPIENT = "recipient";
    public static final String SCOPE_EXTERNAL = "external";

    @Id
    @Column(name = "run_id", updatable = false, nullable = false)
    @Builder.Default
    private UUID runId = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Null for an ad hoc definition. */
    @Column(name = "template_id", updatable = false)
    private UUID templateId;

    @Column(name = "schedule_id", updatable = false)
    private UUID scheduleId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    /** The recipient a per-recipient scheduled run is generated for (scope {@code recipient}). */
    @Column(name = "for_user_id", updatable = false)
    private UUID forUserId;

    @Column(name = "delivery_scope", nullable = false, updatable = false)
    @Builder.Default
    private String deliveryScope = SCOPE_OWNER;

    @Column(name = "trigger", nullable = false, updatable = false)
    private String triggerType;

    @Column(name = "format", nullable = false, updatable = false)
    private String format;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private ReportRunStatus status = ReportRunStatus.queued;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition_snapshot", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> definitionSnapshot;

    @Column(name = "scheduled_for", updatable = false)
    private OffsetDateTime scheduledFor;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "truncated", nullable = false)
    private boolean truncated;

    @Column(name = "progress_rows", nullable = false)
    private long progressRows;

    @Column(name = "file_object_key")
    private String fileObjectKey;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "storage_location_id")
    private UUID storageLocationId;

    @Column(name = "error_code")
    private String errorCode;

    /** Sanitised and user-safe: no SQL, no stack, no object keys. The full cause is in the log under the run id. */
    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    /** Null: not a delivered run. False with note EMPTY: a scheduled report with no data the organisation chose not to send. */
    @Column(name = "delivered")
    private Boolean delivered;

    @Column(name = "delivery_note")
    private String deliveryNote;

    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
