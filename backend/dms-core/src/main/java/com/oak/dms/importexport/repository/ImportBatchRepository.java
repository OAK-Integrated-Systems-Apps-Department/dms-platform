package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.ImportBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
     * claim the same item or both pass the batch-size check (and a cancel
     * can't race an upload or a submit).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ImportBatch b where b.importBatchId = :importBatchId and b.tenantId = :tenantId"
            + " and b.createdBy = :createdBy and b.deleted = false")
    Optional<ImportBatch> lockByImportBatchIdAndTenantIdAndCreatedBy(@Param("importBatchId") UUID importBatchId,
                                                                     @Param("tenantId") UUID tenantId,
                                                                     @Param("createdBy") UUID createdBy);

    /**
     * Row-locked lookup by id alone, for {@code BulkImportWorker} only: a
     * queue message carries no tenant or user, so the worker takes both
     * from the locked row instead. Never call this from request handling -
     * use the tenant- and creator-scoped lock above there.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ImportBatch b where b.importBatchId = :importBatchId and b.deleted = false")
    Optional<ImportBatch> lockByImportBatchId(@Param("importBatchId") UUID importBatchId);

    /** The caller's newest batch in one of {@code statuses} - the one counting toward the active-batch limit. */
    Optional<ImportBatch> findFirstByTenantIdAndCreatedByAndStatusInAndDeletedFalseOrderByCreatedAtDesc(
            UUID tenantId, UUID createdBy, Collection<ImportBatch.ImportBatchStatus> statuses);

    /** The caller's batches in every status - the "Recent imports" list. Ordering comes from {@code pageable}. */
    Page<ImportBatch> findAllByTenantIdAndCreatedByAndDeletedFalse(UUID tenantId, UUID createdBy, Pageable pageable);

    long countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(UUID tenantId, UUID createdBy,
                                                               Collection<ImportBatch.ImportBatchStatus> statuses);
}
