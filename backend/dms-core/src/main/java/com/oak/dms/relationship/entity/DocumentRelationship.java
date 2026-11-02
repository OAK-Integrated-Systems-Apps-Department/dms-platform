package com.oak.dms.relationship.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One link between two different documents (V111). One row per pair - the inverse is derived
 * when reading. A removed link keeps its row ({@code removedAt} set) so the history survives.
 */
@Entity
@Table(name = "document_relationships")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentRelationship {

    public static final String ORIGIN_MANUAL = "manual";
    public static final String ORIGIN_DUPLICATE_DETECTION = "duplicate_detection";
    public static final String ORIGIN_BULK = "bulk";
    public static final String ORIGIN_VERSION_EVENT = "version_event";

    public static final String REMOVED_BY_USER = "user_removed";
    public static final String REMOVED_TARGET_PURGED = "target_purged";

    @Id
    @UuidGenerator
    @Column(name = "relationship_id", updatable = false, nullable = false)
    private UUID relationshipId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "relationship_type_id", nullable = false)
    private UUID relationshipTypeId;

    @Column(name = "source_document_id", nullable = false, updatable = false)
    private UUID sourceDocumentId;

    @Column(name = "target_document_id", nullable = false, updatable = false)
    private UUID targetDocumentId;

    /** Version pins for the automatic Supersedes on publish (deferred); nothing writes them yet. */
    @Column(name = "source_version_id")
    private UUID sourceVersionId;

    @Column(name = "target_version_id")
    private UUID targetVersionId;

    @Column(name = "origin", nullable = false)
    @Builder.Default
    private String origin = ORIGIN_MANUAL;

    @Column(name = "batch_id", updatable = false)
    private UUID batchId;

    @Column(name = "note")
    private String note;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "removed_at")
    private OffsetDateTime removedAt;

    @Column(name = "removed_by")
    private UUID removedBy;

    @Column(name = "removed_reason")
    private String removedReason;

    public boolean isActive() {
        return removedAt == null;
    }
}
