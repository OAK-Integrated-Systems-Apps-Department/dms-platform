package com.oak.dms.workflow.entity;

import com.oak.dms.workflow.enums.WorkflowActionType;
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
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One entry of an instance's timeline (V45) - the local, queryable history
 * the UI shows. Append-only, so no audit block. Compliance export is the
 * audit module's job (decision 13). {@link #actorUserId} is null for system
 * actions (auto-advance, version-change handling).
 */
@Entity
@Table(name = "workflow_actions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowAction {

    @Id
    @UuidGenerator
    @Column(name = "action_id", updatable = false, nullable = false)
    private UUID actionId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "instance_id", nullable = false, updatable = false)
    private UUID instanceId;

    @Column(name = "stage_instance_id", updatable = false)
    private UUID stageInstanceId;

    @Column(name = "task_id", updatable = false)
    private UUID taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, updatable = false)
    private WorkflowActionType actionType;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "reason", updatable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false, updatable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> detail = new HashMap<>();

    @Column(name = "occurred_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime occurredAt = OffsetDateTime.now();
}
