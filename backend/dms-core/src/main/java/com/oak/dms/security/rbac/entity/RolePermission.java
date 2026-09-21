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

/**
 * Grants a permission from the platform-wide {@link Permission} catalog,
 * optionally scoped to a resource type (e.g. "document", "folder",
 * "report"), to a role. This is the many-to-many join between {@link Role}
 * and {@link Permission}: a role can hold many permissions and a
 * permission can be granted to many roles, each grant tracked as its own
 * row here. As with {@link UserRole}, roleId/permissionId are kept as
 * plain UUID columns rather than JPA relationships and are resolved
 * through their own repositories where a caller needs the related row.
 * Uses partial standard fields (created_at only) per the confirmed data
 * dictionary - membership rows are added/removed, never edited in place.
 */
@Entity
@Table(name = "role_permissions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_role_permissions", columnNames = {"role_id", "permission_id", "resource_scope"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RolePermission {

    @Id
    @UuidGenerator
    @Column(name = "role_permission_id", updatable = false, nullable = false)
    private UUID rolePermissionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "permission_id", nullable = false)
    private UUID permissionId;

    @Column(name = "resource_scope")
    private String resourceScope;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
