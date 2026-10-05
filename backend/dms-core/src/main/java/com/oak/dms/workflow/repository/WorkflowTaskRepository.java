package com.oak.dms.workflow.repository;

import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.TaskStatus;
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

public interface WorkflowTaskRepository extends JpaRepository<WorkflowTask, UUID> {

    Optional<WorkflowTask> findByTaskIdAndTenantIdAndDeletedFalse(UUID taskId, UUID tenantId);

    /**
     * The task's stage instance id, as a scalar - deliberately NOT loading the
     * entity. A row read before its FOR UPDATE stays in the persistence
     * context, and the later lock query hands back that stale copy.
     */
    @Query("select t.stageInstanceId from WorkflowTask t where t.taskId = :id and t.tenantId = :tenantId and t.deleted = false")
    Optional<UUID> findStageInstanceIdOfTask(@Param("id") UUID taskId, @Param("tenantId") UUID tenantId);

    /**
     * LOCK 3 - the stage's open tasks, in task_id order so two transactions
     * can never take them in opposite orders. Always after the stage lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from WorkflowTask t
            where t.stageInstanceId = :stageInstanceId and t.status in :open and t.deleted = false
            order by t.taskId
            """)
    List<WorkflowTask> lockOpenByStageInstance(@Param("stageInstanceId") UUID stageInstanceId,
                                               @Param("open") Collection<TaskStatus> open);

    List<WorkflowTask> findByStageInstanceIdAndDeletedFalseOrderByCreatedAtAsc(UUID stageInstanceId);

    List<WorkflowTask> findByStageInstanceIdInAndDeletedFalse(Collection<UUID> stageInstanceIds);

    /** WA-FR-013: has this user already decided a task of this stage instance? */
    boolean existsByStageInstanceIdAndActedByAndStatusIn(UUID stageInstanceId, UUID actedBy,
                                                         Collection<TaskStatus> statuses);

    /**
     * Claim a pool task without an explicit lock: one conditional UPDATE that
     * only takes this task's row lock, so it can never deadlock with the
     * approve path (§6). 0 rows = someone else claimed it, or it closed.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update WorkflowTask t
            set t.status = com.oak.dms.workflow.enums.TaskStatus.claimed, t.claimedBy = :userId, t.claimedAt = :now,
                t.updatedBy = :userId, t.updatedAt = :now
            where t.taskId = :taskId and t.tenantId = :tenantId
              and t.status = com.oak.dms.workflow.enums.TaskStatus.pending
              and t.assigneeType <> com.oak.dms.workflow.enums.AssigneeType.user
            """)
    int claim(@Param("taskId") UUID taskId, @Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
              @Param("now") OffsetDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update WorkflowTask t
            set t.status = com.oak.dms.workflow.enums.TaskStatus.pending, t.claimedBy = null, t.claimedAt = null,
                t.updatedBy = :userId, t.updatedAt = :now
            where t.taskId = :taskId and t.tenantId = :tenantId
              and t.status = com.oak.dms.workflow.enums.TaskStatus.claimed and t.claimedBy = :userId
            """)
    int release(@Param("taskId") UUID taskId, @Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
                @Param("now") OffsetDateTime now);

    /**
     * The caller's queue (W10): open tasks of active stages of in-progress
     * instances the caller didn't start, that the caller can act on, minus
     * stages they already decided. Each include* flag switches one branch on:
     * user tasks addressed to them, pool tasks they claimed, and unclaimed
     * pool tasks of their roles / their department. roleIds must not be empty
     * (pass a placeholder) - Hibernate renders an empty IN list unreliably.
     */
    @Query(value = """
            select t from WorkflowTask t, WorkflowStageInstance si, WorkflowInstance i
            where t.stageInstanceId = si.stageInstanceId and si.instanceId = i.instanceId
              and t.tenantId = :tenantId and t.deleted = false
              and t.status in (com.oak.dms.workflow.enums.TaskStatus.pending, com.oak.dms.workflow.enums.TaskStatus.claimed)
              and si.status = com.oak.dms.workflow.enums.StageInstanceStatus.active
              and i.status = com.oak.dms.workflow.enums.InstanceStatus.in_progress
              and i.initiatedBy <> :userId
              and (
                   (:includeUser = true and t.assigneeType = com.oak.dms.workflow.enums.AssigneeType.user and t.assigneeId = :userId)
                or (:includeClaimed = true and t.status = com.oak.dms.workflow.enums.TaskStatus.claimed and t.claimedBy = :userId)
                or (:includePool = true and t.status = com.oak.dms.workflow.enums.TaskStatus.pending
                    and ((t.assigneeType = com.oak.dms.workflow.enums.AssigneeType.role and t.assigneeId in :roleIds)
                      or (t.assigneeType = com.oak.dms.workflow.enums.AssigneeType.department and t.assigneeId = :departmentId)))
              )
              and not exists (select d.taskId from WorkflowTask d
                              where d.stageInstanceId = t.stageInstanceId and d.actedBy = :userId
                                and d.status in (com.oak.dms.workflow.enums.TaskStatus.approved, com.oak.dms.workflow.enums.TaskStatus.rejected))
            """,
            countQuery = """
            select count(t) from WorkflowTask t, WorkflowStageInstance si, WorkflowInstance i
            where t.stageInstanceId = si.stageInstanceId and si.instanceId = i.instanceId
              and t.tenantId = :tenantId and t.deleted = false
              and t.status in (com.oak.dms.workflow.enums.TaskStatus.pending, com.oak.dms.workflow.enums.TaskStatus.claimed)
              and si.status = com.oak.dms.workflow.enums.StageInstanceStatus.active
              and i.status = com.oak.dms.workflow.enums.InstanceStatus.in_progress
              and i.initiatedBy <> :userId
              and (
                   (:includeUser = true and t.assigneeType = com.oak.dms.workflow.enums.AssigneeType.user and t.assigneeId = :userId)
                or (:includeClaimed = true and t.status = com.oak.dms.workflow.enums.TaskStatus.claimed and t.claimedBy = :userId)
                or (:includePool = true and t.status = com.oak.dms.workflow.enums.TaskStatus.pending
                    and ((t.assigneeType = com.oak.dms.workflow.enums.AssigneeType.role and t.assigneeId in :roleIds)
                      or (t.assigneeType = com.oak.dms.workflow.enums.AssigneeType.department and t.assigneeId = :departmentId)))
              )
              and not exists (select d.taskId from WorkflowTask d
                              where d.stageInstanceId = t.stageInstanceId and d.actedBy = :userId
                                and d.status in (com.oak.dms.workflow.enums.TaskStatus.approved, com.oak.dms.workflow.enums.TaskStatus.rejected))
            """)
    Page<WorkflowTask> findMyTasks(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
                                   @Param("roleIds") Collection<UUID> roleIds,
                                   @Param("departmentId") UUID departmentId,
                                   @Param("includeUser") boolean includeUser,
                                   @Param("includeClaimed") boolean includeClaimed,
                                   @Param("includePool") boolean includePool,
                                   Pageable pageable);
}
