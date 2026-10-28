package com.oak.dms.workflow.service;

import com.oak.dms.workflow.dto.DocumentWorkflowStatusResponse;
import com.oak.dms.workflow.dto.MyTaskCountResponse;
import com.oak.dms.workflow.dto.MyTaskResponse;
import com.oak.dms.workflow.dto.WorkflowActionView;
import com.oak.dms.workflow.dto.WorkflowInstanceResponse;
import com.oak.dms.workflow.dto.WorkflowInstanceSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Read side (W8/W10). An instance is visible to its initiator, anyone who
 * claimed or decided one of its tasks, anyone who can act on one of its open
 * tasks, and holders of wa.view-workflow-audit-trail.view (tenant admins
 * implicitly) - anyone else gets 403; another tenant's instance 404s.
 */
public interface WorkflowQueryService {

    /**
     * all | assigned (addressed, claimed, delegated or escalated to me) |
     * available (unclaimed pool tasks) | claimed | waiting (my tasks paused
     * by an information request).
     */
    enum MyTasksView { all, assigned, available, claimed, waiting }

    WorkflowInstanceResponse getInstance(UUID instanceId);

    Page<WorkflowInstanceSummaryResponse> documentHistory(UUID documentId, Pageable pageable);

    /**
     * The newest run of each of these documents, for a list of documents.
     * Documents the caller can't open, and documents never submitted, are
     * simply left out - a missing row says nothing about whether it exists.
     */
    List<DocumentWorkflowStatusResponse> documentStatuses(Collection<UUID> documentIds);

    Page<WorkflowActionView> timeline(UUID instanceId, Pageable pageable);

    Page<MyTaskResponse> myTasks(MyTasksView view, Pageable pageable);

    MyTaskCountResponse myTaskCount();

    /**
     * 404 for another tenant's instance, 403 (AccessDeniedException) unless
     * the caller may see it - the same rule as {@link #getInstance}.
     */
    void requireVisible(UUID instanceId);
}
