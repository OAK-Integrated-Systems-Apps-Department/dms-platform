package com.oak.dms.workflow.entity;

import com.oak.dms.workflow.enums.CompletionRule;
import com.oak.dms.workflow.enums.EscalationTarget;
import com.oak.dms.workflow.enums.RejectionRule;
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
 * One ordered stage of a template version (V44). {@link #stageOrderUniqKey}
 * is the generated column behind the deferrable, soft-delete-aware
 * stage_order uniqueness - read-only here, like DocumentType's
 * display_order_uniq_key. Reminder/escalation fields are stored in Phase 1
 * and acted on in Phase 2.
 */
@Entity
@Table(name = "workflow_stages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowStage {

    @Id
    @UuidGenerator
    @Column(name = "stage_id", updatable = false, nullable = false)
    private UUID stageId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Column(name = "stage_order", nullable = false)
    private int stageOrder;

    @Column(name = "stage_order_uniq_key", insertable = false, updatable = false)
    private Integer stageOrderUniqKey;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_rule", nullable = false)
    private CompletionRule completionRule;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_rule", nullable = false)
    private RejectionRule rejectionRule;

    @Column(name = "sla_hours")
    private Integer slaHours;

    @Column(name = "reminder_before_hours")
    private Integer reminderBeforeHours;

    @Enumerated(EnumType.STRING)
    @Column(name = "escalation_target", nullable = false)
    @Builder.Default
    private EscalationTarget escalationTarget = EscalationTarget.none;

    @Column(name = "escalation_user_id")
    private UUID escalationUserId;

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
