package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.enums.ExportJobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for {@link ExportJob}. Every tenant-facing read takes the
 * tenant id, so one tenant can never load another's job by guessing an id.
 */
public interface ExportJobRepository extends JpaRepository<ExportJob, UUID> {

    Optional<ExportJob> findByExportJobIdAndTenantId(UUID exportJobId, UUID tenantId);

    /** Newest first - the order a "my exports" list would show. */
    List<ExportJob> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, ExportJobStatus status);

    /** "My exports": one user's jobs in one tenant, paged and sorted by the caller. */
    Page<ExportJob> findByTenantIdAndRequestedBy(UUID tenantId, UUID requestedBy, Pageable pageable);

    Page<ExportJob> findByTenantIdAndRequestedByAndStatus(UUID tenantId, UUID requestedBy, ExportJobStatus status,
                                                          Pageable pageable);

    /**
     * Jobs whose {@code expires_at} has passed, across all tenants, oldest
     * deadline first (index ix_export_jobs_expires_at). For the Phase 4
     * auto-purge sweep - deliberately not scheduled yet. Rows with no
     * expires_at never match.
     */
    List<ExportJob> findByExpiresAtBeforeOrderByExpiresAtAsc(OffsetDateTime now);
}
