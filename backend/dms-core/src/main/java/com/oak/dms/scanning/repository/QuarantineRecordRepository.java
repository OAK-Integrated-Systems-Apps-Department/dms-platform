package com.oak.dms.scanning.repository;

import com.oak.dms.scanning.entity.QuarantineRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface QuarantineRecordRepository extends JpaRepository<QuarantineRecord, UUID> {

    Optional<QuarantineRecord> findByScanResultId(UUID scanResultId);
}
