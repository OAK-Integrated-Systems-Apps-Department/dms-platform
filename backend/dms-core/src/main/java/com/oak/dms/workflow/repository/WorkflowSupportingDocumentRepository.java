package com.oak.dms.workflow.repository;

import com.oak.dms.workflow.entity.WorkflowSupportingDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowSupportingDocumentRepository extends JpaRepository<WorkflowSupportingDocument, UUID> {

    Optional<WorkflowSupportingDocument> findBySupportingDocumentIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);

    List<WorkflowSupportingDocument> findByInstanceIdAndTenantIdAndDeletedFalseOrderByAddedAtAsc(UUID instanceId,
                                                                                               UUID tenantId);

    List<WorkflowSupportingDocument> findByInfoRequestIdInAndTenantIdAndDeletedFalse(Collection<UUID> infoRequestIds,
                                                                                    UUID tenantId);

    boolean existsByInstanceIdAndAddedByAndDeletedFalse(UUID instanceId, UUID addedBy);

    boolean existsByInstanceIdAndDocumentVersionIdAndDeletedFalse(UUID instanceId, UUID documentVersionId);
}
