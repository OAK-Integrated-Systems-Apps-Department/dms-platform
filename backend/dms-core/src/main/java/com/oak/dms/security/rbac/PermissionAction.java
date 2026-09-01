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
 * A verb (e.g. "VIEW", "MANAGE") available under exactly one
 * {@link PermissionModule}. As with {@link RolePermission#getRoleId()},
 * moduleId is kept as a plain UUID column rather than a JPA relationship
 * and is resolved through {@link PermissionModuleRepository} where a
 * caller needs the related row. The same action_code may exist under
 * different modules (e.g. "VIEW" under both "ROLE" and "USER") - it is
 * only unique within its own module, enforced by
 * {@code uq_permission_actions_module_action}. An action owns any number
 * of {@link Permission}s (one-to-many).
 */
@Entity
@Table(name = "permission_actions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_permission_actions_module_action", columnNames = {"module_id", "action_code"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PermissionAction {

    @Id
    @UuidGenerator
    @Column(name = "action_id", updatable = false, nullable = false)
    private UUID actionId;

    @Column(name = "module_id", nullable = false)
    private UUID moduleId;

    /** Stable machine key (e.g. "VIEW", "MANAGE"), unique within its module. */
    @Column(name = "action_code", nullable = false)
    private String actionCode;

    @Column(name = "action_name", nullable = false)
    private String actionName;

    @Column(name = "description")
    private String description;

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
