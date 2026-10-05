package com.oak.dms.workflow.repository;

import com.oak.dms.workflow.entity.WorkflowStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowStageRepository extends JpaRepository<WorkflowStage, UUID> {

    List<WorkflowStage> findByTemplateIdAndTenantIdAndDeletedFalseOrderByStageOrderAsc(UUID templateId, UUID tenantId);

    Optional<WorkflowStage> findByStageIdAndTemplateIdAndTenantIdAndDeletedFalse(UUID stageId, UUID templateId,
                                                                                  UUID tenantId);

    List<WorkflowStage> findByTemplateIdInAndDeletedFalse(Collection<UUID> templateIds);

    long countByTemplateIdAndDeletedFalse(UUID templateId);
}
