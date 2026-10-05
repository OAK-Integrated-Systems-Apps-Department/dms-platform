package com.oak.dms.workflow.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.workflow.dto.ApproveTaskRequest;
import com.oak.dms.workflow.dto.MyTaskCountResponse;
import com.oak.dms.workflow.dto.MyTaskResponse;
import com.oak.dms.workflow.dto.RejectTaskRequest;
import com.oak.dms.workflow.dto.TaskActionResponse;
import com.oak.dms.workflow.enums.TaskDecision;
import com.oak.dms.workflow.service.WorkflowQueryService;
import com.oak.dms.workflow.service.WorkflowTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The caller's approval queue and the actions on a task (W6/W10). A pool
 * task (role/department) can be claimed first, or acted on directly -
 * acting claims it implicitly.
 */
@RestController
@RequestMapping("/api/v1/workflow/tasks")
@RequiredArgsConstructor
@Tag(name = "Workflow tasks", description = "My Tasks, claim/release, approve/reject")
public class WorkflowTaskController {

    private static final String ACT = "@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TASK_APPROVE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TASK_REJECT)";

    private final WorkflowTaskService taskService;
    private final WorkflowQueryService queryService;

    @GetMapping("/my")
    @Operation(summary = "Tasks the caller can act on",
            description = "view: all (default) | assigned (to me, or claimed by me) | available (unclaimed pool tasks) | claimed.")
    public PageResponse<MyTaskResponse> myTasks(
            @RequestParam(required = false) WorkflowQueryService.MyTasksView view,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(queryService.myTasks(view, pageable));
    }

    @GetMapping("/my/count")
    @Operation(summary = "Open and overdue counts for the nav badge")
    public MyTaskCountResponse myTaskCount() {
        return queryService.myTaskCount();
    }

    @PostMapping("/{taskId}/claim")
    @PreAuthorize(ACT)
    @Operation(summary = "Claim a role/department pool task", description = "409 WORKFLOW_TASK_ALREADY_CLAIMED if someone was first.")
    public TaskActionResponse claim(@PathVariable("taskId") UUID taskId) {
        return taskService.claim(taskId);
    }

    @PostMapping("/{taskId}/release")
    @PreAuthorize(ACT)
    @Operation(summary = "Give a claimed task back to its pool")
    public TaskActionResponse release(@PathVariable("taskId") UUID taskId) {
        return taskService.release(taskId);
    }

    @PostMapping("/{taskId}/approve")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TASK_APPROVE)")
    @Operation(summary = "Approve", description = "The stage advances automatically once its completion rule is met.")
    public TaskActionResponse approve(@PathVariable("taskId") UUID taskId,
                                      @Valid @RequestBody(required = false) ApproveTaskRequest request) {
        return taskService.act(taskId, TaskDecision.approve, request == null ? null : request.comment());
    }

    @PostMapping("/{taskId}/reject")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TASK_REJECT)")
    @Operation(summary = "Reject - a reason is mandatory (400 without one)")
    public TaskActionResponse reject(@PathVariable("taskId") UUID taskId, @Valid @RequestBody RejectTaskRequest request) {
        return taskService.act(taskId, TaskDecision.reject, request.reason());
    }
}
