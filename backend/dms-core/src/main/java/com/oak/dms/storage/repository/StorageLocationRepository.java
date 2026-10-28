package com.oak.dms.storage.repository;

import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StorageLocationRepository extends JpaRepository<StorageLocation, UUID> {

    Optional<StorageLocation> findByStorageLocationIdAndDeletedFalse(UUID storageLocationId);

    /** Tenant-registered document locations only - staging/quarantine rows are never listed through the tenant API. */
    List<StorageLocation> findAllByTenantIdAndPurposeAndDeletedFalse(UUID tenantId, StoragePurpose purpose);

    /** The tenant's own active location for {@code purpose}, if it has registered one - see {@link com.oak.dms.storage.service.StorageLocationResolver}. */
    Optional<StorageLocation> findFirstByTenantIdAndPurposeAndStatusAndDeletedFalse(
            UUID tenantId, StoragePurpose purpose, StorageLocation.Status status);

    /**
     * The platform-wide default row for {@code purpose} - {@code tenantId IS NULL} -
     * see {@link com.oak.dms.storage.helper.StorageLocationBootstrap}. At most
     * one per purpose (V39's {@code uq_storage_locations_platform_purpose}).
     */
    @Query("select l from StorageLocation l where l.tenantId is null and l.purpose = :purpose "
            + "and l.status = :status and l.deleted = false")
    Optional<StorageLocation> findPlatformDefault(@Param("purpose") StoragePurpose purpose,
                                                  @Param("status") StorageLocation.Status status);

    boolean existsByTenantIdIsNullAndPurposeAndDeletedFalse(StoragePurpose purpose);
}
