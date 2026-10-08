package com.oak.dms.workflow.entity;

import com.oak.dms.workflow.enums.StageInstanceStatus;
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
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One stage of one instance (V45), created {@code not_started} at
 * submission. Counters are per participant ENTRY; {@link #entryCount} is n,
 * fixed when the stage activates. Always mutated while holding its row lock
 * (decision 6) - see WorkflowEngine.
 */
@Entity
@Table(name = "workflow_stage_instances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowStageInstance {

    @Id
    @UuidGenerator
    @Column(name = "stage_instance_id", updatable = false, nullable = false)
    private UUID stageInstanceId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "instance_id", nullable = false, updatable = false)
    private UUID instanceId;

    @Column(name = "stage_id", nullable = false, updatable = false)
    private UUID stageId;

    @Column(name = "stage_order", nullable = false, updatable = false)
    private int stageOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private StageInstanceStatus status = StageInstanceStatus.not_started;

    @Column(name = "entry_count", nullable = false)
    private int entryCount;

    @Column(name = "required_count", nullable = false)
    private int requiredCount;

    @Column(name = "approved_count", nullable = false)
    private int approvedCount;

    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
