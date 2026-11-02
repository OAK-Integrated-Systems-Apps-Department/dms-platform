package com.oak.dms.ownership.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The summary of one transfer run (V113): a single transfer, a bulk run, an offboarding or a reorganisation.
 * Inserted once, complete, at the end of the run's transaction; the batch id is assigned by the service so the
 * history rows can point at it.
 */
@Entity
@Table(name = "ownership_transfer_batches")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OwnershipTransferBatch {

    public static final String KIND_SINGLE = "single";
    public static final String KIND_BULK = "bulk";
    public static final String KIND_OFFBOARDING = "offboarding";
    public static final String KIND_REORGANIZATION = "reorganization";

    @Id
    @Column(name = "batch_id", updatable = false, nullable = false)
    private UUID batchId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "source_user_id")
    private UUID sourceUserId;

    @Column(name = "source_department_id")
    private UUID sourceDepartmentId;

    @Column(name = "new_owner_user_id", nullable = false)
    private UUID newOwnerUserId;

    @Column(name = "new_owner_department_id")
    private UUID newOwnerDepartmentId;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "transferred_count", nullable = false)
    private int transferredCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skipped_summary", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Integer> skippedSummary = new HashMap<>();

    @Column(name = "access_granted_count", nullable = false)
    private int accessGrantedCount;

    @Column(name = "converted_to_restricted_count", nullable = false)
    private int convertedToRestrictedCount;

    @Column(name = "flagged_task_count", nullable = false)
    private int flaggedTaskCount;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "note")
    private String note;
}
