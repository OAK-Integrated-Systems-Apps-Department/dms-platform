package com.oak.dms.ocrworker;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {

    /** Used by prepareRerun's guard (Phase 1 B.1) -- a fresh job row must
     * only be inserted if none is already active, or it would violate
     * V36's partial unique index on (reference_type, reference_id) WHERE
     * status IN ('queued','processing').
     */
    List<ProcessingJob> findByReferenceTypeAndReferenceIdAndJobTypeAndStatusIn(
            String referenceType, UUID referenceId, String jobType, List<String> statuses);

    /** Used by getOcrStatus's error lookup and OcrEventsConsumer's
     * staleness check -- both need "the current job for this version",
     * not history.
     */
    Optional<ProcessingJob> findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(
            String referenceType, UUID referenceId, String jobType);

    /**
     * How much OCR work this tenant already has in flight - the number
     * {@code OcrEnqueueService} weighs against {@code maxQueuedPerTenant}.
     * <p>
     * Counts queued AND processing. Counting only queued would let the limit
     * be evaded by a slow worker: with ten long-running jobs and an empty
     * queue it would read zero and admit another two thousand.
     */
    // 'ocr' / 'queued' / 'processing' as literals: they are the values V69's
    // own CHECK constraints allow, so the query and the constraint are read
    // from the same place. ProcessingJob's constants hold the same strings.
    @Query("""
            select count(j) from ProcessingJob j
            where j.tenantId = :tenantId
              and j.jobType = 'ocr'
              and j.status in ('queued', 'processing')
            """)
    long countActiveOcrJobsForTenant(@Param("tenantId") UUID tenantId);
}
