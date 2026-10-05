package com.oak.dms.workflow.entity;

import com.oak.dms.workflow.enums.AssigneeType;
import com.oak.dms.workflow.enums.TaskDecision;
import com.oak.dms.workflow.enums.TaskStatus;
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
 * One task per participant entry of an active stage (V45). For a role or
 * department entry the task is a claimable pool: one row, visible to every
 * eligible member, and the first to act satisfies the entry. The
 * delegation/reminder/escalation columns are Phase 2 and stay null here.
 */
@Entity
@Table(name = "workflow_tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowTask {

    @Id
    @UuidGenerator
    @Column(name = "task_id", updatable = false, nullable = false)
    private UUID taskId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "stage_instance_id", nullable = false, updatable = false)
    private UUID stageInstanceId;

    @Column(name = "participant_id", nullable = false, updatable = false)
    private UUID participantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignee_type", nullable = false)
    private AssigneeType assigneeType;

    @Column(name = "assignee_id", nullable = false)
    private UUID assigneeId;

    @Column(name = "claimed_by")
    private UUID claimedBy;

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @Column(name = "delegated_from")
    private UUID delegatedFrom;

    @Column(name = "delegated_to")
    private UUID delegatedTo;

    @Column(name = "delegated_at")
    private OffsetDateTime delegatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private TaskStatus status = TaskStatus.pending;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision")
    private TaskDecision decision;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "acted_by")
    private UUID actedBy;

    @Column(name = "acted_at")
    private OffsetDateTime actedAt;

    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @Column(name = "reminder_sent_at")
    private OffsetDateTime reminderSentAt;

    @Column(name = "escalated_at")
    private OffsetDateTime escalatedAt;

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

    public boolean isPool() {
        return assigneeType != AssigneeType.user;
    }
}
