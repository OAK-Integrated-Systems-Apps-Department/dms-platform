package com.oak.dms.workflow.repository;

import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.enums.InstanceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {

    Optional<WorkflowInstance> findByInstanceIdAndTenantIdAndDeletedFalse(UUID instanceId, UUID tenantId);

    /** LOCK 1 of the workflow lock order (documents -> instances -> stage instances -> tasks). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WorkflowInstance i where i.instanceId = :id and i.tenantId = :tenantId and i.deleted = false")
    Optional<WorkflowInstance> lockByInstanceIdAndTenantId(@Param("id") UUID instanceId, @Param("tenantId") UUID tenantId);

    Optional<WorkflowInstance> findFirstByTenantIdAndDocumentIdAndStatus(UUID tenantId, UUID documentId,
                                                                          InstanceStatus status);

    /** Scalar on purpose - see WorkflowTaskRepository#findStageInstanceIdOfTask. */
    @Query("""
            select i.instanceId from WorkflowInstance i
            where i.tenantId = :tenantId and i.documentId = :documentId
              and i.status = com.oak.dms.workflow.enums.InstanceStatus.in_progress
            """)
    Optional<UUID> findInProgressInstanceId(@Param("tenantId") UUID tenantId, @Param("documentId") UUID documentId);

    Page<WorkflowInstance> findByTenantIdAndDocumentIdAndDeletedFalse(UUID tenantId, UUID documentId, Pageable pageable);

    /** Every run of these documents, newest first - the badges in a list of documents. */
    @Query("select i from WorkflowInstance i where i.tenantId = :tenantId and i.documentId in :documentIds "
            + "and i.deleted = false order by i.startedAt desc")
    List<WorkflowInstance> findByDocuments(@Param("tenantId") UUID tenantId,
                                           @Param("documentIds") Collection<UUID> documentIds);

    boolean existsByTemplateId(UUID templateId);

    /**
     * Advances the instance's signing target to a freshly-stamped version (1.4 signature placement) - a
     * plain update, deliberately WITHOUT {@link #lockByInstanceIdAndTenantId}'s row lock: this runs
     * alongside a document-ingestion/scan call that must never happen while any workflow row lock is
     * held (see {@code DocumentSignatureService}'s own doc comment on the deadlock this avoids). A lost
     * update race here (two approvers stamping at the exact same instant) only means one of them signs a
     * slightly older version than intended, never a security issue - {@code documentVersionId} stays
     * {@code updatable = false} at the entity level everywhere else, so this is the one deliberate,
     * narrow exception, via JPQL rather than a normal save().
     */
    @Modifying
    @Query("update WorkflowInstance i set i.documentVersionId = :versionId "
            + "where i.instanceId = :instanceId and i.tenantId = :tenantId")
    void advanceDocumentVersion(@Param("instanceId") UUID instanceId, @Param("tenantId") UUID tenantId,
                                @Param("versionId") UUID versionId);
}
