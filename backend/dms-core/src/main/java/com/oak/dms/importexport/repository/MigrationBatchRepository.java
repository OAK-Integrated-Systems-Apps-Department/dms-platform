package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.MigrationBatch;
import com.oak.dms.importexport.enums.MigrationBatchStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-facing reads always take the tenant id - same rule as {@link ExportJobRepository}. */
public interface MigrationBatchRepository extends JpaRepository<MigrationBatch, UUID> {

    Optional<MigrationBatch> findByMigrationBatchIdAndTenantIdAndDeletedFalse(UUID migrationBatchId, UUID tenantId);

    Optional<MigrationBatch> findByTenantIdAndCreatedByAndIdempotencyKeyAndDeletedFalse(UUID tenantId, UUID createdBy,
                                                                                        String idempotencyKey);

    /** "My imports". */
    Page<MigrationBatch> findByTenantIdAndCreatedByAndDeletedFalse(UUID tenantId, UUID createdBy, Pageable pageable);

    List<MigrationBatch> findByStatusIn(Collection<MigrationBatchStatus> statuses);

    /** Finished batches whose staged files can be deleted. */
    List<MigrationBatch> findByStatusInAndCompletedAtBefore(Collection<MigrationBatchStatus> statuses,
                                                            OffsetDateTime before);

    /** Serializes count/status updates from the worker and the reconciler. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from MigrationBatch b where b.migrationBatchId = :id")
    Optional<MigrationBatch> lockById(@Param("id") UUID migrationBatchId);
}
