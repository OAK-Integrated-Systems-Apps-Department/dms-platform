package com.oak.dms.workflow.entity;

import com.oak.dms.workflow.enums.SupportingDocumentAccessMode;
import com.oak.dms.workflow.enums.SupportingDocumentSource;
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
 * Evidence attached to a workflow instance (V48), pinned to one document
 * version. Never routed for approval and never subject to the version-change
 * check (decision 17); opening it still needs docm.documents.view.
 */
@Entity
@Table(name = "workflow_supporting_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowSupportingDocument {

    @Id
    @UuidGenerator
    @Column(name = "supporting_document_id", updatable = false, nullable = false)
    private UUID supportingDocumentId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "instance_id", nullable = false, updatable = false)
    private UUID instanceId;

    @Column(name = "info_request_id", updatable = false)
    private UUID infoRequestId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "document_version_id", nullable = false, updatable = false)
    private UUID documentVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false)
    private SupportingDocumentSource source;

    @Column(name = "added_by", nullable = false, updatable = false)
    private UUID addedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_mode", nullable = false)
    @Builder.Default
    private SupportingDocumentAccessMode accessMode = SupportingDocumentAccessMode.already_accessible;

    @Column(name = "access_revoked_at")
    private OffsetDateTime accessRevokedAt;

    @Column(name = "note")
    private String note;

    @Column(name = "added_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime addedAt = OffsetDateTime.now();

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
