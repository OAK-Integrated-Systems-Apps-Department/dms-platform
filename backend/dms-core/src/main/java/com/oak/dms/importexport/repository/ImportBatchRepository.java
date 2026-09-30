package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.ImportBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    Optional<ImportBatch> findByImportBatchIdAndTenantIdAndCreatedByAndDeletedFalse(UUID importBatchId, UUID tenantId,
                                                                                    UUID createdBy);

    /**
     * Row-locked lookup used while a file is staged or a batch is
     * submitted, so concurrent requests against the same batch can't both
     * claim the same item or both pass the batch-size check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ImportBatch b where b.importBatchId = :importBatchId and b.tenantId = :tenantId"
            + " and b.createdBy = :createdBy and b.deleted = false")
    Optional<ImportBatch> lockByImportBatchIdAndTenantIdAndCreatedBy(@Param("importBatchId") UUID importBatchId,
                                                                     @Param("tenantId") UUID tenantId,
                                                                     @Param("createdBy") UUID createdBy);

    long countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(UUID tenantId, UUID createdBy,
                                                               Collection<ImportBatch.ImportBatchStatus> statuses);
}
