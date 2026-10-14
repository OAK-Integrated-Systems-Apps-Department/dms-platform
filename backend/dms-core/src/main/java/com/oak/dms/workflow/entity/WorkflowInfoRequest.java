package com.oak.dms.workflow.entity;

import com.oak.dms.workflow.enums.InfoRequestStatus;
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
 * A formal request-and-pause (V48): the holder of a task asks someone for a
 * document or an answer. While any request on the task is open the task is
 * {@code awaiting_info}, can't be decided, and its SLA clock is paused.
 */
@Entity
@Table(name = "workflow_info_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowInfoRequest {

    @Id
    @UuidGenerator
    @Column(name = "info_request_id", updatable = false, nullable = false)
    private UUID infoRequestId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "instance_id", nullable = false, updatable = false)
    private UUID instanceId;

    @Column(name = "stage_instance_id", nullable = false, updatable = false)
    private UUID stageInstanceId;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_from", nullable = false)
    private UUID requestedFrom;

    @Column(name = "message", nullable = false, updatable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private InfoRequestStatus status = InfoRequestStatus.open;

    @Column(name = "response_message")
    private String responseMessage;

    @Column(name = "decline_reason")
    private String declineReason;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @Column(name = "requested_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime requestedAt = OffsetDateTime.now();

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @Column(name = "responded_by")
    private UUID respondedBy;

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
