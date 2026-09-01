package com.oak.dms.security.rbac;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link PermissionAction}. Not tenant-scoped - see
 * {@link PermissionModule} for why.
 */
public interface PermissionActionRepository extends JpaRepository<PermissionAction, UUID> {

    Optional<PermissionAction> findByActionIdAndDeletedFalse(UUID actionId);

    boolean existsByModuleIdAndActionCodeAndDeletedFalse(UUID moduleId, String actionCode);

    /** Used to guard module deletion - a module with live actions can't be removed. */
    boolean existsByModuleIdAndDeletedFalse(UUID moduleId);

    List<PermissionAction> findAllByModuleIdAndDeletedFalse(UUID moduleId);

    @Query("select a from PermissionAction a where a.deleted = false")
    Page<PermissionAction> findAllActive(Pageable pageable);
}
