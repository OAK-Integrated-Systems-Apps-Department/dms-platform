package com.oak.dms.department.entity;

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
 * A tenant's department (V43). Users point at their primary department via
 * {@code users.department_id}; a workflow stage can name a department as a
 * claimable approver pool (direct members only). {@link #headUserId} is the
 * Phase 2 escalation target.
 */
@Entity
@Table(name = "departments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Department {

    @Id
    @UuidGenerator
    @Column(name = "department_id", updatable = false, nullable = false)
    private UUID departmentId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "parent_department_id")
    private UUID parentDepartmentId;

    @Column(name = "head_user_id")
    private UUID headUserId;

    /** Who documents are parked with when their owner leaves and nobody is named (V113); null = fall back to the head. */
    @Column(name = "holding_owner_user_id")
    private UUID holdingOwnerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private DepartmentStatus status = DepartmentStatus.active;

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

    public enum DepartmentStatus {
        active, inactive
    }
}
