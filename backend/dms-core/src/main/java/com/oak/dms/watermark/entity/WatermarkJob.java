package com.oak.dms.watermark.entity;

import com.oak.dms.watermark.enums.WatermarkJobStatus;
import com.oak.dms.watermark.enums.WatermarkSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One requested watermarked copy (V68), produced in the background by
 * {@link com.oak.dms.watermark.job.WatermarkJobWorker} exactly the way
 * {@code ExportJobWorker} produces an export: claim under a pessimistic
 * lock, re-bind the requester's identity, do the work, store the result,
 * complete or fail. The output lives at {@link #outputObjectKey} in the
 * tenant's STAGING location until {@link #expiresAt}, and the only way to it
 * is the result endpoint, which re-checks access every time.
 *
 * <h2>Why the id is assigned rather than generated</h2>
 * {@code {watermarkId}} is a placeholder a user can put in the watermark
 * text: the first 8 characters of this job's id, drawn onto the page so a
 * leaked copy can be traced back to the audit entry that produced it. The
 * text is resolved in the request thread, <em>before</em> the row is
 * written, so the id has to exist before the insert - a generated id would
 * mean drawing one value and storing another, which is worse than not having
 * the feature at all. So {@link #jobId} is assigned by
 * {@code WatermarkService} and this entity implements {@link Persistable} to
 * tell Spring Data the row is still new despite already having an id
 * (without it, {@code save()} would treat a set id as an existing row and
 * issue a pointless SELECT-then-merge). {@code WatermarkJobIdentityTest}
 * pins the guarantee that the drawn id and the stored id are the same.
 */
@Entity
@Table(name = "watermark_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WatermarkJob implements Persistable<UUID> {

    @Id
    @Column(name = "job_id", updatable = false, nullable = false)
    private UUID jobId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    /** The exact version watermarked - never "whatever is current now", which could move under the job. */
    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    /** Null when the request supplied custom text instead of choosing a template. */
    @Column(name = "template_id", updatable = false)
    private UUID templateId;

    /** The template's {@code version} as it was when this job was requested - see {@link WatermarkTemplate}. */
    @Column(name = "template_version", updatable = false)
    private Integer templateVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false)
    @Builder.Default
    private WatermarkSource source = WatermarkSource.USER_REQUEST;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private WatermarkJobStatus status = WatermarkJobStatus.pending;

    /**
     * The resolved text and effective style as JSON - a
     * {@code WatermarkSpec}. Written once, in the request thread; the worker
     * reads it and never re-resolves, so what was audited is what was drawn.
     * Stored as a String with {@code @JdbcTypeCode(JSON)}, like
     * {@code ExportJob.requestParams}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_snapshot", nullable = false, columnDefinition = "jsonb", updatable = false)
    @Builder.Default
    private String requestSnapshot = "{}";

    @Column(name = "source_mime", updatable = false)
    private String sourceMime;

    @Column(name = "source_size_bytes", updatable = false)
    private Long sourceSizeBytes;

    /** Where {@link #outputObjectKey} lives - never returned to clients. */
    @Column(name = "storage_location_id")
    private UUID storageLocationId;

    /** {@code {tenantId}/tmp/watermarks/{jobId}/{filename}} in staging. Never returned to clients. */
    @Column(name = "output_object_key")
    private String outputObjectKey;

    @Column(name = "output_size_bytes")
    private Long outputSizeBytes;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "failure_code")
    private String failureCode;

    /** Shown to the requester as-is, so it never carries a stack trace or an internal path. */
    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    /** SHA-256 of the normalised request - see V68's comment on why it isn't the resolved snapshot. */
    @Column(name = "request_hash", updatable = false)
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    /** Set once the output has been deleted by the expiry sweep; the row itself stays as the record. */
    @Column(name = "purged_at")
    private OffsetDateTime purgedAt;

    /**
     * True until this instance has been written or loaded. Not a column -
     * see the class comment for why an assigned id needs it.
     */
    @Transient
    @Builder.Default
    private boolean newRecord = true;

    @Override
    public UUID getId() {
        return jobId;
    }

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newRecord = false;
    }
}
