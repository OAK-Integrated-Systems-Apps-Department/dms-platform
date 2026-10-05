package com.oak.dms.workflow.repository;

import com.oak.dms.workflow.entity.WorkflowStageParticipant;
import com.oak.dms.workflow.enums.AssigneeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WorkflowStageParticipantRepository extends JpaRepository<WorkflowStageParticipant, UUID> {

    List<WorkflowStageParticipant> findByStageIdAndTenantIdAndDeletedFalseOrderByDisplayOrderAscCreatedAtAsc(
            UUID stageId, UUID tenantId);

    List<WorkflowStageParticipant> findByStageIdInAndDeletedFalseOrderByDisplayOrderAscCreatedAtAsc(
            Collection<UUID> stageIds);

    /**
     * Whether a role/department/user is still named by a live stage of a
     * live template - blocks deleting a department a template still routes to.
     */
    @Query("""
            select count(p) > 0 from WorkflowStageParticipant p, WorkflowStage s, WorkflowTemplate t
            where p.stageId = s.stageId and s.templateId = t.templateId
              and p.tenantId = :tenantId and p.assigneeType = :type and p.assigneeId = :assigneeId
              and p.deleted = false and s.deleted = false and t.deleted = false
            """)
    boolean isAssigneeInUse(@Param("tenantId") UUID tenantId, @Param("type") AssigneeType type,
                            @Param("assigneeId") UUID assigneeId);
}
