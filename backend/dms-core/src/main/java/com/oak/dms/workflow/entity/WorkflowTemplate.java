package com.oak.dms.workflow.entity;

import com.oak.dms.workflow.enums.EscalationTarget;
import com.oak.dms.workflow.enums.OnDocumentChange;
import com.oak.dms.workflow.enums.TemplateStatus;
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
 * One VERSION of an approval chain for a document type (V44). Versions of
 * the same template share {@link #code}; each is its own row so an in-flight
 * instance keeps pointing at immutable stages. Only {@code draft} rows are
 * edited - everything else is versioned via a new draft copy.
 */
@Entity
@Table(name = "workflow_templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowTemplate {

    @Id
    @UuidGenerator
    @Column(name = "template_id", updatable = false, nullable = false)
    private UUID templateId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "document_type_id", nullable = false)
    private UUID documentTypeId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "description")
    private String description;

    @Column(name = "version_number", nullable = false, updatable = false)
    @Builder.Default
    private int versionNumber = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private TemplateStatus status = TemplateStatus.draft;

    @Enumerated(EnumType.STRING)
    @Column(name = "on_document_change", nullable = false)
    @Builder.Default
    private OnDocumentChange onDocumentChange = OnDocumentChange.flag_only;

    // 3.2 Notification reminder/escalation defaults (N-FR-003/004/006); stages
    // may override. null = no default (that rule off unless a stage sets it).
    @Column(name = "default_reminder_before_hours")
    private Integer defaultReminderBeforeHours;

    @Column(name = "default_inactivity_reminder_hours")
    private Integer defaultInactivityReminderHours;

    @Column(name = "default_escalate_after_reminder_hours")
    private Integer defaultEscalateAfterReminderHours;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_escalation_target", nullable = false)
    @Builder.Default
    private EscalationTarget defaultEscalationTarget = EscalationTarget.none;

    @Column(name = "default_escalation_user_id")
    private UUID defaultEscalationUserId;

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
