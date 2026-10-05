package com.oak.dms.workflow.service;

import com.oak.dms.workflow.dto.MyTaskCountResponse;
import com.oak.dms.workflow.dto.MyTaskResponse;
import com.oak.dms.workflow.dto.WorkflowActionView;
import com.oak.dms.workflow.dto.WorkflowInstanceResponse;
import com.oak.dms.workflow.dto.WorkflowInstanceSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Read side (W8/W10). An instance is visible to its initiator, anyone who
 * claimed or decided one of its tasks, anyone who can act on one of its open
 * tasks, and holders of wa.view-workflow-audit-trail.view (tenant admins
 * implicitly) - anyone else gets 403; another tenant's instance 404s.
 */
public interface WorkflowQueryService {

    enum MyTasksView { all, assigned, available, claimed }

    WorkflowInstanceResponse getInstance(UUID instanceId);

    Page<WorkflowInstanceSummaryResponse> documentHistory(UUID documentId, Pageable pageable);

    Page<WorkflowActionView> timeline(UUID instanceId, Pageable pageable);

    Page<MyTaskResponse> myTasks(MyTasksView view, Pageable pageable);

    MyTaskCountResponse myTaskCount();
}
