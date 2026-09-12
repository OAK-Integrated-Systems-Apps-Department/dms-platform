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
@Table(name = "permissions")
/**
 * A single grantable capability (e.g. "role.manage", "document.approve") in
 * the permission catalog. Hybrid tenant-scoping via {@link #tenantId}: null
 * means it's part of the platform's shared/default catalog (every
 * permission seeded by a migration, visible to and grantable by every
 * tenant); a real tenant id means a tenant created it themselves through
 * the Permission Catalog screens, and it's visible/manageable only within
 * that tenant. Unlike {@link Role} (always tenant-scoped) or the old
 * fully-global design this replaced, a permission's tenant boundary is
 * therefore optional rather than mandatory - see
 * {@link com.oak.dms.security.rbac.PermissionServiceImpl} for how reads
 * and writes enforce it. {@link PermissionAction}/{@link PermissionModule}
 * stay fully global regardless - only the permission itself, not the
 * taxonomy it's filed under, can be tenant-specific.
 * <p>
 * permission_code's uniqueness is enforced by two partial unique indexes
 * (see V11__add_tenant_scoping_to_permissions.sql) rather than the single
 * {@code uniqueConstraints} this class used to declare: a code must be
 * unique among the shared catalog, and independently unique within each
 * tenant's own custom permissions, but two different tenants may each use
 * the same code without conflicting with one another.
 * <p>
 * This is the many-to-many join partner for {@link Role}: a permission
 * can be granted to any number of roles, and a role can hold any number
 * of permissions.
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
     * Null for the platform's shared catalog; a real tenant id for a
     * permission that tenant created themselves. See this class's own
     * doc comment for the full hybrid-scoping rationale.
     */
    @Column(name = "tenant_id")
    private UUID tenantId;

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

    /**
     * True for a permission kept only for backward compatibility - no
     * longer checked by any {@code @authz.hasPermission(...)} call and
     * hidden from the Role builder's permission picker (see
     * {@code use-permission-catalog.ts}), but left grantable/queryable
     * like any other row so existing {@link RolePermission} grants aren't
     * disturbed. Set for the 4 original coarse codes (role.view/
     * role.manage/user.view/user.manage) by V15, once their granular
     * equivalents took over every {@code @PreAuthorize} check that used
     * to reference them.
     */
    @Column(name = "is_deprecated", nullable = false)
    @Builder.Default
    private boolean deprecated = false;
}
