package com.oak.dms.ownership.repository;

import com.oak.dms.ownership.entity.OwnershipTransferBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OwnershipTransferBatchRepository extends JpaRepository<OwnershipTransferBatch, UUID> {

    Optional<OwnershipTransferBatch> findByBatchIdAndTenantId(UUID batchId, UUID tenantId);
}
