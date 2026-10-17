package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.ImportBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    long countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(UUID tenantId, UUID createdBy,
                                                               Collection<ImportBatch.ImportBatchStatus> statuses);

    /** "Recent imports": one user's batches in one tenant, paged and sorted by the caller. */
    Page<ImportBatch> findByTenantIdAndCreatedByAndDeletedFalse(UUID tenantId, UUID createdBy, Pageable pageable);

    /**
     * Takes one of this batch's OCR slots if any are left (V84). Returns 1 when
     * the slot was taken, 0 when the batch is already at {@code cap}.
     *
     * <p>Conditional UPDATE rather than read-then-write: several of a batch's
     * rows are in flight at once, and each spends minutes inside the scan gate
     * between a read and a write. The {@code WHERE ... < :cap} is what makes
     * exactly one of two threads racing for the last slot win - the database
     * decides, not the interleaving.
     *
     * <p>{@code @Modifying(clearAutomatically)} because the caller may hold a
     * stale {@code ImportBatch} in the persistence context; without it the old
     * count could be flushed back over this one.
     */
    // Its own transaction: ImportRecordCreator calls this from outside one
    // (the scan gate commits its own steps), and a claimed slot must stay
    // claimed even if the gate call that follows it fails.
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ImportBatch b set b.ocrQueuedCount = b.ocrQueuedCount + 1 "
            + "where b.importBatchId = :importBatchId and b.ocrQueuedCount < :cap")
    int claimOcrSlot(@Param("importBatchId") UUID importBatchId, @Param("cap") int cap);
}
