package com.oak.dms.workflow.repository;

import com.oak.dms.workflow.entity.WorkflowStageInstance;
import com.oak.dms.workflow.enums.StageInstanceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowStageInstanceRepository extends JpaRepository<WorkflowStageInstance, UUID> {

    Optional<WorkflowStageInstance> findByStageInstanceIdAndTenantId(UUID stageInstanceId, UUID tenantId);

    /** Scalar on purpose - see WorkflowTaskRepository#findStageInstanceIdOfTask. */
    @Query("select s.instanceId from WorkflowStageInstance s where s.stageInstanceId = :id and s.tenantId = :tenantId")
    Optional<UUID> findInstanceIdOfStage(@Param("id") UUID stageInstanceId, @Param("tenantId") UUID tenantId);

    /** LOCK 2 - always taken after the owning instance's lock (decision 6). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WorkflowStageInstance s where s.stageInstanceId = :id and s.tenantId = :tenantId")
    Optional<WorkflowStageInstance> lockByStageInstanceIdAndTenantId(@Param("id") UUID stageInstanceId,
                                                                     @Param("tenantId") UUID tenantId);

    List<WorkflowStageInstance> findByInstanceIdOrderByStageOrderAsc(UUID instanceId);

    List<WorkflowStageInstance> findByInstanceIdInOrderByStageOrderAsc(Collection<UUID> instanceIds);

    Optional<WorkflowStageInstance> findFirstByInstanceIdAndStatusOrderByStageOrderAsc(UUID instanceId,
                                                                                         StageInstanceStatus status);
}
