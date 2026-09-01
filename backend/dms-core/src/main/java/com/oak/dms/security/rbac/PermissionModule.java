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
 * Top-level grouping in the platform-wide permission catalog (e.g. "ROLE",
 * "USER", "DOCUMENT"). Not tenant-scoped, same as {@link Permission}: every
 * tenant's roles draw permissions from the same catalog, organized under
 * the same modules. A module owns any number of {@link PermissionAction}s
 * (one-to-many); a module cannot be deleted out from under actions that
 * still reference it (see {@code fk_permission_actions_module}).
 */
@Entity
@Table(name = "permission_modules", uniqueConstraints = {
        @UniqueConstraint(name = "uq_permission_modules_code", columnNames = {"module_code"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PermissionModule {

    @Id
    @UuidGenerator
    @Column(name = "module_id", updatable = false, nullable = false)
    private UUID moduleId;

    /**
     * Stable machine key (e.g. "ROLE", "DOCUMENT"). Uppercase by
     * convention, matched against the module prefix of
     * {@link Permission#getPermissionCode()} (e.g. "role.view" -> "ROLE").
     */
    @Column(name = "module_code", nullable = false)
    private String moduleCode;

    @Column(name = "module_name", nullable = false)
    private String moduleName;

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
