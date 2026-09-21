package com.oak.dms.storage.repository;

import com.oak.dms.storage.entity.StorageLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StorageLocationRepository extends JpaRepository<StorageLocation, UUID> {

    Optional<StorageLocation> findByStorageLocationIdAndDeletedFalse(UUID storageLocationId);

    List<StorageLocation> findAllByTenantIdAndDeletedFalse(UUID tenantId);

    /** The tenant's own active location, if it has registered one - see {@link com.oak.dms.storage.service.StorageLocationResolver}. */
    Optional<StorageLocation> findFirstByTenantIdAndStatusAndDeletedFalse(UUID tenantId, StorageLocation.Status status);

    /** The platform-wide default row(s) - {@code tenantId IS NULL} - see {@link com.oak.dms.storage.helper.StorageLocationBootstrap}. */
    @Query("select l from StorageLocation l where l.tenantId is null and l.status = :status and l.deleted = false")
    Optional<StorageLocation> findFirstPlatformDefault(@Param("status") StorageLocation.Status status);

    boolean existsByTenantIdIsNullAndDeletedFalse();
}
