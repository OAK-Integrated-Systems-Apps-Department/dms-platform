package com.oak.dms.workflow.repository;

import com.oak.dms.workflow.entity.WorkflowAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Append-only: nothing here updates or deletes (V45 also revokes UPDATE/DELETE). */
public interface WorkflowActionRepository extends JpaRepository<WorkflowAction, UUID> {

    Page<WorkflowAction> findByInstanceIdAndTenantId(UUID instanceId, UUID tenantId, Pageable pageable);

    List<WorkflowAction> findByInstanceIdOrderByOccurredAtAsc(UUID instanceId);
}
