package com.oak.dms.scanning.repository;

import com.oak.dms.scanning.entity.ScanResult;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScanResultRepository extends JpaRepository<ScanResult, UUID>, ScanResultRepositoryCustom {

    Optional<ScanResult> findByScanResultIdAndTenantIdAndDeletedFalse(UUID scanResultId, UUID tenantId);

    /** Row lock for a state transition - taken inside each short transaction of {@code IngestionProcessor}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ScanResult r where r.scanResultId = :id and r.deleted = false")
    Optional<ScanResult> lockById(@Param("id") UUID scanResultId);

    /** Release attempts (re-scans and overrides) of one quarantined scan result, newest first. */
    List<ScanResult> findByReleasedFromScanResultIdOrderByCreatedAtDesc(UUID releasedFromScanResultId);

    List<ScanResult> findByScanResultIdIn(Collection<UUID> scanResultIds);
}
