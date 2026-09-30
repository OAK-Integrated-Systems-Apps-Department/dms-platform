package com.oak.dms.scanning.repository;

import com.oak.dms.scanning.entity.QuarantineRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface QuarantineRecordRepository extends JpaRepository<QuarantineRecord, UUID>,
        JpaSpecificationExecutor<QuarantineRecord> {

    Optional<QuarantineRecord> findByScanResultId(UUID scanResultId);

    /** Tenant-scoped lookup for the admin API - another tenant's id looks like it doesn't exist. */
    Optional<QuarantineRecord> findByQuarantineIdAndTenantId(UUID quarantineId, UUID tenantId);

    /** Row lock for a review action - release, override and delete all serialise on the record. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QuarantineRecord q where q.quarantineId = :id and q.tenantId = :tenantId")
    Optional<QuarantineRecord> lockByIdAndTenant(@Param("id") UUID quarantineId, @Param("tenantId") UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QuarantineRecord q where q.scanResultId = :scanResultId")
    Optional<QuarantineRecord> lockByScanResultId(@Param("scanResultId") UUID scanResultId);
}
