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
 * Assigns a role to a user, optionally scoped narrower than the whole
 * tenant (department / folder / document). scopeId is intentionally NOT a
 * foreign key: it is polymorphic and its target table depends on
 * scopeType, per the confirmed data dictionary.
 */
@Entity
@Table(name = "user_roles", uniqueConstraints = {
        @UniqueConstraint(name = "uq_user_roles", columnNames = {"user_id", "role_id", "scope_type", "scope_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRole {

    @Id
    @UuidGenerator
    @Column(name = "user_role_id", updatable = false, nullable = false)
    private UUID userRoleId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    @Builder.Default
    private ScopeType scopeType = ScopeType.tenant;

    /** Polymorphic target id. Null when scopeType = tenant. No FK by design. */
    @Column(name = "scope_id")
    private UUID scopeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public enum ScopeType {
        tenant, department, folder, document
    }
}
