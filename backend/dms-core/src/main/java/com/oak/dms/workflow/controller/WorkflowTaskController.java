package com.oak.dms.workflow.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.signature.dto.SignAndApproveRequest;
import com.oak.dms.workflow.dto.ApproveTaskRequest;
import com.oak.dms.workflow.dto.DelegateTaskRequest;
import com.oak.dms.workflow.dto.MyTaskCountResponse;
import com.oak.dms.workflow.dto.MyTaskResponse;
import com.oak.dms.workflow.dto.PersonOptionResponse;
import com.oak.dms.workflow.dto.ReassignTaskRequest;
import com.oak.dms.workflow.dto.RejectTaskRequest;
import com.oak.dms.workflow.dto.RetractTaskRequest;
import com.oak.dms.workflow.dto.ReviewLockResponse;
import com.oak.dms.workflow.dto.ReviewTaskRequest;
import com.oak.dms.workflow.dto.TaskActionResponse;
import com.oak.dms.workflow.enums.TaskDecision;
import com.oak.dms.workflow.service.WorkflowDelegationService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
    private final WorkflowDelegationService delegationService;
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

    @PostMapping("/{taskId}/sign-and-approve")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TASK_APPROVE) "
            + "and @authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DSI_SIGN)")
    @Operation(summary = "1.4 Digital Signature Integration: sign AND approve in one transaction",
            description = "Required instead of plain /approve when the stage has requires_signature=true (409 "
                    + "WORKFLOW_SIGNATURE_REQUIRED from /approve otherwise). Needs explicit consent plus step-up "
                    + "re-authentication (password, and TOTP if MFA is enabled) - refused while impersonating. If "
                    + "the signature cannot be recorded for any reason, nothing is approved (fail closed).")
    public TaskActionResponse signAndApprove(@PathVariable("taskId") UUID taskId,
                                             @Valid @RequestBody SignAndApproveRequest request) {
        return taskService.actWithSignature(taskId, request);
    }

    @PostMapping("/{taskId}/delegate")
    @PreAuthorize(ACT)
    @Operation(summary = "Hand my task to someone else",
            description = "The same task moves to the delegate; both are notified. Only its holder may delegate - "
                    + "claim a pool task first (409 WORKFLOW_TASK_NOT_CLAIMED). Delegating back to the original "
                    + "holder clears the delegation. 422 for the initiator, someone who already decided in this "
                    + "stage, or someone without an approve/reject permission.")
    public TaskActionResponse delegate(@PathVariable("taskId") UUID taskId,
                                       @Valid @RequestBody DelegateTaskRequest request) {
        return delegationService.delegate(taskId, request.delegateUserId(), request.note());
    }

    @PostMapping("/{taskId}/reassign")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TASK_REASSIGN)")
    @Operation(summary = "Admin: move any open task to another user (reason required)",
            description = "Also unblocks a task whose pool has nobody eligible. A task waiting on information has "
                    + "its open requests cancelled first.")
    public TaskActionResponse reassign(@PathVariable("taskId") UUID taskId,
                                       @Valid @RequestBody ReassignTaskRequest request) {
        return delegationService.reassign(taskId, request.userId(), request.reason());
    }

    private static final String RETRACT = "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TASK_RETRACT)";

    @PostMapping("/{taskId}/review")
    @PreAuthorize(RETRACT)
    @Operation(summary = "V119: re-open your own already-decided task for review",
            description = "Step-up re-authentication (password, TOTP if MFA is enabled) is required just to view "
                    + "it again, not only to retract. Also acquires a soft lock on the stage, refusing every other "
                    + "entry's claim/approve/reject while it's held (expires on its own if never renewed or closed). "
                    + "409 WORKFLOW_RETRACTION_WINDOW_CLOSED if a later decision has already closed the stage.")
    public ReviewLockResponse review(@PathVariable("taskId") UUID taskId, @Valid @RequestBody ReviewTaskRequest request) {
        return taskService.openForReview(taskId, request.password(), request.totp());
    }

    @PostMapping("/{taskId}/review/heartbeat")
    @PreAuthorize(RETRACT)
    @Operation(summary = "Renew the review lock while still looking", description = "409 if the session already ended (expired or closed) - call review again to reopen it.")
    public ReviewLockResponse reviewHeartbeat(@PathVariable("taskId") UUID taskId) {
        return taskService.renewReviewLock(taskId);
    }

    @DeleteMapping("/{taskId}/review")
    @PreAuthorize(RETRACT)
    @Operation(summary = "End the review session with no change", description = "Releases the lock if you hold it; a no-op otherwise.")
    public void closeReview(@PathVariable("taskId") UUID taskId) {
        taskService.closeReview(taskId);
    }

    @PostMapping("/{taskId}/retract")
    @PreAuthorize(RETRACT)
    @Operation(summary = "Retract your own approval and make the task actionable again",
            description = "A reason is mandatory, and step-up re-authentication is required exactly like signing. "
                    + "409 WORKFLOW_RETRACTION_WINDOW_CLOSED once the stage has closed (a later decision already "
                    + "recorded). If a signature was placed, it's invalidated and the document reverts to the "
                    + "version from before it was signed.")
    public TaskActionResponse retract(@PathVariable("taskId") UUID taskId, @Valid @RequestBody RetractTaskRequest request) {
        return taskService.retract(taskId, request.reason(), request.password(), request.totp());
    }

    @GetMapping("/{taskId}/delegate-candidates")
    @PreAuthorize(ACT + " or @authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_TASK_REASSIGN)")
    @Operation(summary = "People this task could be delegated or reassigned to")
    public List<PersonOptionResponse> delegateCandidates(@PathVariable("taskId") UUID taskId,
                                                         @RequestParam(required = false) String search,
                                                         @RequestParam(defaultValue = "20") int limit) {
        return delegationService.delegateCandidates(taskId, search, limit);
    }
}
