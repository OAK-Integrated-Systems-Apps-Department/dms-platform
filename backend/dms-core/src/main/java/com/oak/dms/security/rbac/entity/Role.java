package com.oak.dms.security.rbac.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "roles", uniqueConstraints = {
        @UniqueConstraint(name = "uq_roles_tenant_name", columnNames = {"tenant_id", "role_name"})
})
/**
 * A named, tenant-scoped bundle of permissions (e.g. "Reviewer",
 * "Department Head"). Concrete permission grants live in
 * {@link RolePermission}; assignment to a specific user lives in
 * {@link UserRole}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role {

    @Id
    @UuidGenerator
    @Column(name = "role_id", updatable = false, nullable = false)
    private UUID roleId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "role_name", nullable = false)
    private String roleName;

    @Column(name = "description")
    private String description;

    /**
     * Activate/deactivate flag shown on Role List and Add/Edit Role.
     * Distinct from {@link #deleted}: an inactive role still exists and
     * can be reactivated, whereas a deleted role is gone (soft-deleted,
     * same as {@link com.oak.dms.user.User#isDeleted()}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private RoleStatus status = RoleStatus.active;

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

    public enum RoleStatus {
        active, inactive
    }
}
