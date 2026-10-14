package com.oak.dms.workflow.repository;

import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.enums.InstanceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    boolean existsByTemplateId(UUID templateId);
}
