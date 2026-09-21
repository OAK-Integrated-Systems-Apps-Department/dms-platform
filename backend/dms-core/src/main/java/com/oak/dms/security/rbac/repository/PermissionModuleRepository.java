package com.oak.dms.security.rbac.repository;

import com.oak.dms.security.rbac.entity.PermissionModule;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link PermissionModule}. Not tenant-scoped - see
 * {@link PermissionModule} for why.
 */
public interface PermissionModuleRepository extends JpaRepository<PermissionModule, UUID> {

    Optional<PermissionModule> findByModuleIdAndDeletedFalse(UUID moduleId);

    boolean existsByModuleCodeAndDeletedFalse(String moduleCode);

    @Query("select m from PermissionModule m where m.deleted = false")
    Page<PermissionModule> findAllActive(Pageable pageable);
}
