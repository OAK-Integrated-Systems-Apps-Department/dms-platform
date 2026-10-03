package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.ImportBatch;
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

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    Optional<ImportBatch> findByImportBatchIdAndTenantIdAndCreatedByAndDeletedFalse(UUID importBatchId, UUID tenantId,
                                                                                    UUID createdBy);

    /**
     * Row-locked lookup used while a file is staged or a batch is
     * submitted, so concurrent requests against the same batch can't both
     * claim the same item or both pass the batch-size check (and a cancel
     * can't race an upload or a submit). A resubmit takes it on the
     * ORIGINAL batch while it claims that batch's kept files, so two
     * resubmits (or a resubmit and the purge job) can't both take one.
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

    /**
     * Finished batches ({@code statuses}) that completed before
     * {@code cutoff} and still have at least one staged file - the
     * candidates of {@code RetainedImportFilePurger}, oldest first. Not
     * locked; {@link #lockForRetainedFilePurge} claims each one.
     */
    @Query("""
            select b.importBatchId from ImportBatch b
             where b.status in :statuses
               and b.completedAt < :cutoff
               and exists (select 1 from ImportItem i
                            where i.importBatchId = b.importBatchId
                              and i.stagedObjectKey is not null)
             order by b.completedAt asc, b.importBatchId asc
            """)
    List<UUID> findBatchIdsWithExpiredStagedFiles(@Param("statuses") Collection<ImportBatch.ImportBatchStatus> statuses,
                                                  @Param("cutoff") OffsetDateTime cutoff, Pageable pageable);

    /**
     * Row-locks a batch for {@code RetainedImportFilePurger} - empty if
     * another instance of the job, or a resubmit claiming its files, holds
     * it right now ({@code SKIP LOCKED}); the job tries again next pass.
     * Deliberately ignores {@code is_deleted}: a deleted batch's files
     * still have to go.
     */
    @Query(value = """
            select * from import_batches
             where import_batch_id = :importBatchId
            for update skip locked
            """, nativeQuery = true)
    Optional<ImportBatch> lockForRetainedFilePurge(@Param("importBatchId") UUID importBatchId);

    /**
     * SQL for a batch's last activity: the latest of its own created_at /
     * updated_at and its items' created_at / updated_at. Postgres
     * {@code greatest} ignores NULLs, and created_at never is one. The item
     * subquery uses the (import_batch_id, row_number) unique index. Expects
     * the batch aliased {@code b}.
     */
    String LAST_ACTIVITY = " greatest(b.created_at, b.updated_at,"
            + " (select max(greatest(i.created_at, i.updated_at)) from import_items i"
            + " where i.import_batch_id = b.import_batch_id)) ";

    /**
     * Batches in one of {@code statuses} whose last activity (see
     * {@link #LAST_ACTIVITY}) is before {@code cutoff}, least recently active
     * first - the candidates of {@code ImportBatchRecoveryJob}. Not locked;
     * {@link #lockForRecovery} claims each one.
     */
    @Query(value = "select c.import_batch_id from ("
            + " select b.import_batch_id," + LAST_ACTIVITY + "as last_activity"
            + " from import_batches b"
            + " where b.status in (:statuses) and b.is_deleted = false) c"
            + " where c.last_activity < :cutoff"
            + " order by c.last_activity asc, c.import_batch_id asc"
            + " limit :limit", nativeQuery = true)
    List<UUID> findInactiveBatchIds(@Param("statuses") Collection<String> statuses,
                                    @Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);

    /**
     * Whether the batch's last activity (see {@link #LAST_ACTIVITY}) is
     * before {@code cutoff} - {@code ImportBatchRecoveryJob}'s re-check once
     * it holds the batch lock.
     */
    @Query(value = "select exists (select 1 from import_batches b"
            + " where b.import_batch_id = :importBatchId"
            + " and" + LAST_ACTIVITY + "< :cutoff)", nativeQuery = true)
    boolean isInactiveSince(@Param("importBatchId") UUID importBatchId, @Param("cutoff") OffsetDateTime cutoff);

    /**
     * Row-locks a batch for {@code ImportBatchRecoveryJob} - empty if it is
     * deleted, or anything else holds it right now ({@code SKIP LOCKED}): an
     * upload, a submit or cancel, the worker claiming it, the finaliser, or
     * another instance of the job. The job tries again next pass.
     */
    @Query(value = """
            select * from import_batches
             where import_batch_id = :importBatchId
               and is_deleted = false
            for update skip locked
            """, nativeQuery = true)
    Optional<ImportBatch> lockForRecovery(@Param("importBatchId") UUID importBatchId);

    /** The caller's newest batch in one of {@code statuses} - the one counting toward the active-batch limit. */
    Optional<ImportBatch> findFirstByTenantIdAndCreatedByAndStatusInAndDeletedFalseOrderByCreatedAtDesc(
            UUID tenantId, UUID createdBy, Collection<ImportBatch.ImportBatchStatus> statuses);

    /** The caller's batches in every status - the "Recent imports" list. Ordering comes from {@code pageable}. */
    Page<ImportBatch> findAllByTenantIdAndCreatedByAndDeletedFalse(UUID tenantId, UUID createdBy, Pageable pageable);

    long countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(UUID tenantId, UUID createdBy,
                                                               Collection<ImportBatch.ImportBatchStatus> statuses);
}
