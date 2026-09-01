package com.oak.dms.security.rbac;

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
@Table(name = "permissions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_permissions_code", columnNames = {"permission_code"})
})
/**
 * A single grantable capability (e.g. "role.manage", "document.approve") in
 * the platform-wide permission catalog. Unlike {@link Role}, permissions are
 * NOT tenant-scoped - every tenant draws from the same catalog; only the
 * grant of a permission to a role, tracked in {@link RolePermission}, is
 * tenant-scoped. This is the many-to-many join partner for {@link Role}: a
 * permission can be granted to any number of roles, and a role can hold any
 * number of permissions.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission {

    @Id
    @UuidGenerator
    @Column(name = "permission_id", updatable = false, nullable = false)
    private UUID permissionId;

    /**
     * Stable machine key matched against {@code @authz.hasPermission(...)}
     * checks throughout the codebase (see AuthorizationService). Treated as
     * immutable once created - see {@link com.oak.dms.security.rbac.dto.UpdatePermissionRequest}.
     */
    @Column(name = "permission_code", nullable = false)
    private String permissionCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    /**
     * FK to the {@link PermissionAction} this permission belongs to (which
     * in turn belongs to exactly one {@link PermissionModule}). Kept as a
     * plain UUID column rather than a JPA relationship and resolved through
     * {@link PermissionActionRepository}, matching how {@link RolePermission}
     * keeps roleId/permissionId as plain UUIDs. Replaces the old free-form
     * `category` text column - grouping is now a real, reusable hierarchy
     * instead of a string.
     */
    @Column(name = "action_id", nullable = false)
    private UUID actionId;

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
