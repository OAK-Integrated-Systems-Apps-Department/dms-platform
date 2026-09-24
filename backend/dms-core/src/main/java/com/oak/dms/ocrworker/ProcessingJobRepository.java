package com.oak.dms.ocrworker;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
