package com.oak.dms.workflow.repository;

import com.oak.dms.workflow.entity.WorkflowTemplate;
import com.oak.dms.workflow.enums.TemplateStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowTemplateRepository extends JpaRepository<WorkflowTemplate, UUID>,
        JpaSpecificationExecutor<WorkflowTemplate> {

    Optional<WorkflowTemplate> findByTemplateIdAndTenantIdAndDeletedFalse(UUID templateId, UUID tenantId);

    /** Row lock for edits to a draft and its stages - serialises concurrent builders on one template. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from WorkflowTemplate t where t.templateId = :id and t.tenantId = :tenantId and t.deleted = false")
    Optional<WorkflowTemplate> lockByTemplateIdAndTenantId(@Param("id") UUID templateId, @Param("tenantId") UUID tenantId);

    /** Every active template of this document type - zero, one, or more since V121 (decision 7 superseded). */
    List<WorkflowTemplate> findByTenantIdAndDocumentTypeIdAndStatusAndDeletedFalse(UUID tenantId,
                                                                                    UUID documentTypeId,
                                                                                    TemplateStatus status);

    List<WorkflowTemplate> findByTenantIdAndCodeAndDeletedFalseOrderByVersionNumberDesc(UUID tenantId, String code);

    boolean existsByTenantIdAndCodeAndDeletedFalse(UUID tenantId, String code);

    boolean existsByTenantIdAndCodeAndStatusAndDeletedFalse(UUID tenantId, String code, TemplateStatus status);

    /** The active version of this template family, if any - at most one (uq_workflow_templates_one_active_per_code). */
    Optional<WorkflowTemplate> findByTenantIdAndCodeAndStatusAndDeletedFalse(UUID tenantId, String code, TemplateStatus status);

    @Query("select coalesce(max(t.versionNumber), 0) from WorkflowTemplate t where t.tenantId = :tenantId and t.code = :code and t.deleted = false")
    int findMaxVersionNumber(@Param("tenantId") UUID tenantId, @Param("code") String code);
}
