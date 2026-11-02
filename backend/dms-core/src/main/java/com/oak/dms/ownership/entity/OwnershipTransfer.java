package com.oak.dms.ownership.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One change of a document's owner (V123). Append-only: a trigger rejects UPDATE and DELETE, so there are no
 * setters. User ids are plain columns (no foreign key) and the names are snapshots, so the row stays readable
 * after a user is permanently deleted.
 */
@Entity
@Table(name = "ownership_transfers")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OwnershipTransfer {

    public static final String REASON_MANUAL = "manual";
    public static final String REASON_OFFBOARDING = "offboarding";
    public static final String REASON_REORGANIZATION = "reorganization";
    public static final String REASON_HOLDING = "holding";

    @Id
    @UuidGenerator
    @Column(name = "transfer_id", updatable = false, nullable = false)
    private UUID transferId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "previous_owner_user_id", nullable = false, updatable = false)
    private UUID previousOwnerUserId;

    @Column(name = "previous_owner_department_id", updatable = false)
    private UUID previousOwnerDepartmentId;

    @Column(name = "new_owner_user_id", nullable = false, updatable = false)
    private UUID newOwnerUserId;

    @Column(name = "new_owner_department_id", updatable = false)
    private UUID newOwnerDepartmentId;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "batch_id", updatable = false)
    private UUID batchId;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Column(name = "access_granted", nullable = false, updatable = false)
    private boolean accessGranted;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "previous_owner_name", updatable = false)
    private String previousOwnerName;

    @Column(name = "new_owner_name", updatable = false)
    private String newOwnerName;

    @Column(name = "actor_name", updatable = false)
    private String actorName;

    @Column(name = "transferred_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime transferredAt = OffsetDateTime.now();
}
