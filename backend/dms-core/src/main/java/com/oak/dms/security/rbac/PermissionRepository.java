package com.oak.dms.security.rbac;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link Permission}. Not tenant-scoped - see {@link Permission}
 * for why - so unlike most repositories in this package, lookups here never
 * filter by tenantId.
 */
public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    Optional<Permission> findByPermissionIdAndDeletedFalse(UUID permissionId);

    boolean existsByPermissionCodeAndDeletedFalse(String permissionCode);

    /** Used to guard action deletion - an action with live permissions can't be removed. */
    boolean existsByActionIdAndDeletedFalse(UUID actionId);

    @Query("select p from Permission p where p.deleted = false")
    Page<Permission> findAllActive(Pageable pageable);
}
