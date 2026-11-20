package com.oak.dms.reporting.repository;

import com.oak.dms.reporting.entity.ReportRun;
import com.oak.dms.reporting.run.ReportRunStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportRunRepository extends JpaRepository<ReportRun, UUID>, JpaSpecificationExecutor<ReportRun> {

    Optional<ReportRun> findByRunIdAndTenantId(UUID runId, UUID tenantId);

    long countByTenantIdAndRequestedByAndStatusIn(UUID tenantId, UUID requestedBy, Collection<ReportRunStatus> statuses);

    long countByTenantIdAndStatus(UUID tenantId, ReportRunStatus status);

    /** Oldest first: the queue poller starts what it can, in the order it was asked for. */
    List<ReportRun> findByStatusOrderByCreatedAtAsc(ReportRunStatus status, Pageable page);

    List<ReportRun> findByStatusAndExpiresAtBefore(ReportRunStatus status, OffsetDateTime cutoff, Pageable page);

    List<ReportRun> findByStatusAndStartedAtBefore(ReportRunStatus status, OffsetDateTime cutoff, Pageable page);

    @Query("select r.status from ReportRun r where r.runId = :id")
    Optional<ReportRunStatus> statusOf(@Param("id") UUID runId);

    /** Rows written so far; only while the run is still running, so a late write cannot touch a finished run. */
    @Modifying
    @Query("update ReportRun r set r.progressRows = :rows where r.runId = :id and r.status = com.oak.dms.reporting.run.ReportRunStatus.running")
    int updateProgress(@Param("id") UUID runId, @Param("rows") long rows);

    /** Run history older than the retention period. A run that still has a stored file is never deleted: expire it first. */
    @Transactional
    @Modifying
    @Query("delete from ReportRun r where r.createdAt < :cutoff and r.status in :finished and r.fileObjectKey is null")
    int deleteHistoryBefore(@Param("cutoff") OffsetDateTime cutoff, @Param("finished") Collection<ReportRunStatus> finished);
}
