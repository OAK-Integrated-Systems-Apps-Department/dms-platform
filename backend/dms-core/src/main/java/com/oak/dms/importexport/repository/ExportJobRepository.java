package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.entity.ExportJob.ExportJobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExportJobRepository extends JpaRepository<ExportJob, UUID> {

    long countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(UUID tenantId, UUID createdBy,
                                                               Collection<ExportJobStatus> statuses);

    Page<ExportJob> findAllByTenantIdAndCreatedByAndDeletedFalse(UUID tenantId, UUID createdBy, Pageable pageable);

    Optional<ExportJob> findByExportJobIdAndTenantIdAndCreatedByAndDeletedFalse(UUID exportJobId, UUID tenantId,
                                                                                 UUID createdBy);

    /**
     * Row-locked lookup by id alone, for {@code BulkExportWorker} only: a
     * queue message carries no tenant or user, so the worker takes both
     * from the locked row instead. Never call this from request handling.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ExportJob j where j.exportJobId = :exportJobId and j.deleted = false")
    Optional<ExportJob> lockByExportJobId(@Param("exportJobId") UUID exportJobId);

    /**
     * The worker's heartbeat: marks the job as still moving, so
     * {@code BulkExportCleanupJob} never takes a long export that is still
     * being built for a stuck one.
     */
    @Modifying
    @Query("update ExportJob j set j.updatedAt = :now where j.exportJobId = :exportJobId")
    int touch(@Param("exportJobId") UUID exportJobId, @Param("now") OffsetDateTime now);

    /**
     * Completed exports due their expiry warning - not expired yet at
     * {@code now}, expiring at or before {@code warnBefore}, and not warned
     * yet (one already past expiry just expires, unwarned). Candidates of
     * {@code BulkExportCleanupJob}; not locked, {@link #lockForCleanup}
     * claims each one.
     */
    @Query("""
            select j.exportJobId from ExportJob j
             where j.status = com.oak.dms.importexport.entity.ExportJob.ExportJobStatus.completed
               and j.expiresAt > :now
               and j.expiresAt <= :warnBefore
               and j.expiryWarningSentAt is null
             order by j.expiresAt asc, j.exportJobId asc
            """)
    List<UUID> findExportIdsDueExpiryWarning(@Param("now") OffsetDateTime now,
                                             @Param("warnBefore") OffsetDateTime warnBefore, Pageable pageable);

    /** Completed exports whose file has expired - see {@link #findExportIdsDueExpiryWarning}. */
    @Query("""
            select j.exportJobId from ExportJob j
             where j.status = com.oak.dms.importexport.entity.ExportJob.ExportJobStatus.completed
               and j.expiresAt <= :now
             order by j.expiresAt asc, j.exportJobId asc
            """)
    List<UUID> findExpiredExportIds(@Param("now") OffsetDateTime now, Pageable pageable);

    /**
     * Pending or processing exports with no activity since {@code cutoff}
     * - last activity is updated_at (the worker's heartbeat), or
     * created_at for a job nothing has touched yet. Not their age: a long
     * export the worker is still moving is never a candidate.
     */
    @Query("""
            select j.exportJobId from ExportJob j
             where j.status in :statuses
               and coalesce(j.updatedAt, j.createdAt) < :cutoff
             order by j.createdAt asc, j.exportJobId asc
            """)
    List<UUID> findInactiveExportIds(@Param("statuses") Collection<ExportJobStatus> statuses,
                                     @Param("cutoff") OffsetDateTime cutoff, Pageable pageable);

    /**
     * Row-locks an export for {@code BulkExportCleanupJob} - empty if
     * another instance of the job, or the worker, holds it right now
     * ({@code SKIP LOCKED}); the job tries again next pass. Ignores
     * {@code is_deleted}, so a deleted job's file still goes.
     */
    @Query(value = """
            select * from export_jobs
             where export_job_id = :exportJobId
            for update skip locked
            """, nativeQuery = true)
    Optional<ExportJob> lockForCleanup(@Param("exportJobId") UUID exportJobId);
}
