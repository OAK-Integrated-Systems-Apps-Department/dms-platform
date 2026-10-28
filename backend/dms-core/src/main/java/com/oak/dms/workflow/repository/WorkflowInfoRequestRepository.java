package com.oak.dms.workflow.repository;

import com.oak.dms.workflow.entity.WorkflowInfoRequest;
import com.oak.dms.workflow.enums.InfoRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowInfoRequestRepository extends JpaRepository<WorkflowInfoRequest, UUID> {

    Optional<WorkflowInfoRequest> findByInfoRequestIdAndTenantIdAndDeletedFalse(UUID infoRequestId, UUID tenantId);

    /** Scalar parent ids of a request - never the entity, which must first be read by its FOR UPDATE. */
    @Query("""
            select r.instanceId, r.stageInstanceId, r.taskId from WorkflowInfoRequest r
            where r.infoRequestId = :id and r.tenantId = :tenantId and r.deleted = false
            """)
    List<Object[]> findParentIds(@Param("id") UUID infoRequestId, @Param("tenantId") UUID tenantId);

    /**
     * LOCK 5 - the open requests of these tasks, in info_request_id order,
     * always after the task locks (decision 22).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from WorkflowInfoRequest r
            where r.tenantId = :tenantId and r.taskId in :taskIds
              and r.status = com.oak.dms.workflow.enums.InfoRequestStatus.open and r.deleted = false
            order by r.infoRequestId
            """)
    List<WorkflowInfoRequest> lockOpenByTaskIds(@Param("tenantId") UUID tenantId,
                                                @Param("taskIds") Collection<UUID> taskIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from WorkflowInfoRequest r
            where r.infoRequestId = :id and r.tenantId = :tenantId and r.deleted = false
            """)
    Optional<WorkflowInfoRequest> lockByIdAndTenantId(@Param("id") UUID infoRequestId,
                                                      @Param("tenantId") UUID tenantId);

    long countByTaskIdAndStatusAndDeletedFalse(UUID taskId, InfoRequestStatus status);

    List<WorkflowInfoRequest> findByInstanceIdAndTenantIdAndDeletedFalseOrderByRequestedAtAsc(UUID instanceId,
                                                                                            UUID tenantId);

    boolean existsByInstanceIdAndRequestedFromAndDeletedFalse(UUID instanceId, UUID requestedFrom);

    /** "Requests for me" - requests addressed to the user, newest first; status null means any. */
    @Query(value = """
            select r from WorkflowInfoRequest r
            where r.tenantId = :tenantId and r.requestedFrom = :userId and r.deleted = false
              and (:status is null or r.status = :status)
            order by r.requestedAt desc
            """,
            countQuery = """
            select count(r) from WorkflowInfoRequest r
            where r.tenantId = :tenantId and r.requestedFrom = :userId and r.deleted = false
              and (:status is null or r.status = :status)
            """)
    org.springframework.data.domain.Page<WorkflowInfoRequest> findAddressedTo(
            @Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("status") InfoRequestStatus status,
            org.springframework.data.domain.Pageable pageable);

    long countByTenantIdAndRequestedFromAndStatusAndDeletedFalse(UUID tenantId, UUID requestedFrom,
                                                                 InfoRequestStatus status);

    @Query("""
            select r.taskId, count(r) from WorkflowInfoRequest r
            where r.tenantId = :tenantId and r.taskId in :taskIds
              and r.status = com.oak.dms.workflow.enums.InfoRequestStatus.open and r.deleted = false
            group by r.taskId
            """)
    List<Object[]> countOpenByTaskIds(@Param("tenantId") UUID tenantId, @Param("taskIds") Collection<UUID> taskIds);
}
