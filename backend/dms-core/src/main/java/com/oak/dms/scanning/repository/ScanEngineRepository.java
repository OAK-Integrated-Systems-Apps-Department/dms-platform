package com.oak.dms.scanning.repository;

import com.oak.dms.scanning.entity.ScanEngine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ScanEngineRepository extends JpaRepository<ScanEngine, UUID> {

    Optional<ScanEngine> findByScanEngineIdAndDeletedFalse(UUID scanEngineId);

    /** The tenant's own active engine, if it has registered one - see {@link com.oak.dms.scanning.service.ScanEngineResolver}. */
    Optional<ScanEngine> findFirstByTenantIdAndStatusAndDeletedFalse(UUID tenantId, ScanEngine.Status status);

    /** The platform-wide default row - {@code tenantId IS NULL}, at most one active (V39). */
    @Query("select e from ScanEngine e where e.tenantId is null and e.status = :status and e.deleted = false")
    Optional<ScanEngine> findPlatformDefault(@Param("status") ScanEngine.Status status);

    boolean existsByTenantIdIsNullAndDeletedFalse();
}
