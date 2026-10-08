package com.oak.dms.watermark.repository;

import com.oak.dms.watermark.entity.WatermarkJob;
import com.oak.dms.watermark.enums.WatermarkJobStatus;
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

/**
 * Data access for {@link WatermarkJob}, following {@code ExportJobRepository}
 * exactly: every tenant-facing read takes the tenant id, and the worker
 * claims a job under a pessimistic lock so the request side, the worker and
 * the expiry sweep can never interleave on one row.
 */
public interface WatermarkJobRepository extends JpaRepository<WatermarkJob, UUID> {

    /**
     * One job, scoped to the person who asked for it. A watermarked copy
     * carries its requester's name, so even inside a tenant it isn't
     * somebody else's to fetch - "their own jobs only" is the rule, not a
     * convenience.
     */
    Optional<WatermarkJob> findByJobIdAndTenantIdAndRequestedBy(UUID jobId, UUID tenantId, UUID requestedBy);

    /**
     * One page of the caller's own jobs - "My watermarked copies". Scoped by
     * tenant AND requester in the query itself rather than filtered
     * afterwards, so there is no arrangement of parameters that returns
     * somebody else's row.
     */
    Page<WatermarkJob> findByTenantIdAndRequestedBy(UUID tenantId, UUID requestedBy, Pageable pageable);

    Page<WatermarkJob> findByTenantIdAndRequestedByAndStatus(UUID tenantId, UUID requestedBy,
                                                             WatermarkJobStatus status, Pageable pageable);

    /**
     * The LIVE job an Idempotency-Key still refers to, if there is one.
     * <p>
     * Scoped to the same three statuses as ux_watermark_jobs_idempotency, and
     * for the same reason: a key stops meaning anything once its job failed
     * or its output expired, because there is no longer a result to hand
     * back. A client retrying with a stable key then gets a NEW job rather
     * than being permanently stuck on a dead one. Older rows with the same
     * key stay in the table as history, which is why this can't simply query
     * by key alone.
     */
    @Query("""
            select j from WatermarkJob j
            where j.tenantId = :tenantId
              and j.requestedBy = :requestedBy
              and j.idempotencyKey = :idempotencyKey
              and j.status in (com.oak.dms.watermark.enums.WatermarkJobStatus.pending,
                               com.oak.dms.watermark.enums.WatermarkJobStatus.processing,
                               com.oak.dms.watermark.enums.WatermarkJobStatus.completed)
            """)
    Optional<WatermarkJob> findLiveByIdempotencyKey(@Param("tenantId") UUID tenantId,
                                                    @Param("requestedBy") UUID requestedBy,
                                                    @Param("idempotencyKey") String idempotencyKey);

    /** The per-user cap on jobs in flight - the hard limit that survives Redis being down. */
    long countByTenantIdAndRequestedByAndStatusIn(UUID tenantId, UUID requestedBy,
                                                  Collection<WatermarkJobStatus> statuses);

    /** Jobs not finished yet - resumed (or failed as stuck) at startup. */
    List<WatermarkJob> findByStatusIn(Collection<WatermarkJobStatus> statuses);

    /**
     * Finished jobs whose output is past its expiry, oldest first and capped
     * by the caller's page size - the sweep works in batches so it never
     * holds the shared scheduler thread for long.
     */
    @Query("""
            select j from WatermarkJob j
            where j.status = com.oak.dms.watermark.enums.WatermarkJobStatus.completed
              and j.purgedAt is null
              and j.outputObjectKey is not null
              and j.expiresAt is not null
              and j.expiresAt < :now
            order by j.expiresAt asc
            """)
    List<WatermarkJob> findExpiredOutputs(@Param("now") OffsetDateTime now,
                                          org.springframework.data.domain.Pageable page);

    /**
     * Outputs still sitting in storage for documents that have since been
     * deleted or purged. The expiry sweep would catch these within a day
     * anyway; this makes it the next sweep instead, without coupling
     * {@code RecycleBinService} to this module.
     */
    @Query("""
            select j from WatermarkJob j
            where j.purgedAt is null
              and j.outputObjectKey is not null
              and exists (select 1 from Document d
                          where d.documentId = j.documentId
                            and (d.deleted = true or d.purgedAt is not null))
            """)
    List<WatermarkJob> findOutputsOfDeletedDocuments(org.springframework.data.domain.Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from WatermarkJob j where j.jobId = :id")
    Optional<WatermarkJob> lockById(@Param("id") UUID jobId);
}
