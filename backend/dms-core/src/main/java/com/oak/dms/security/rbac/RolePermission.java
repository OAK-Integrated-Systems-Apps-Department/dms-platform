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

/**
 * Grants a single permission code (optionally scoped to a resource type,
 * e.g. "document", "folder", "report") to a role. Uses partial standard
 * fields (created_at only) per the confirmed data dictionary - membership
 * rows are added/removed, never edited in place.
 */
@Entity
@Table(name = "role_permissions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_role_permissions", columnNames = {"role_id", "permission_code", "resource_scope"})
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

    @Column(name = "permission_code", nullable = false)
    private String permissionCode;

    @Column(name = "resource_scope")
    private String resourceScope;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
