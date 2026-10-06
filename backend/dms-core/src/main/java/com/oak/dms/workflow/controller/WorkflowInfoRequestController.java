package com.oak.dms.workflow.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.workflow.dto.DeclineInfoRequest;
import com.oak.dms.workflow.dto.InfoRequestView;
import com.oak.dms.workflow.dto.RaiseInfoRequest;
import com.oak.dms.workflow.dto.ReassignInfoRequest;
import com.oak.dms.workflow.dto.RespondInfoRequest;
import com.oak.dms.workflow.dto.WithdrawInfoRequest;
import com.oak.dms.workflow.enums.InfoRequestStatus;
import com.oak.dms.workflow.service.WorkflowInfoRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Information requests (3.1 Phase 2, decisions 11-16). Raising one needs
 * wa.request-workflow-information.create plus being able to act on the
 * task; answering or declining needs only to be the person asked (A8).
 */
@RestController
@RequestMapping("/api/v1/workflow")
@RequiredArgsConstructor
@Tag(name = "Workflow information requests")
public class WorkflowInfoRequestController {

    private final WorkflowInfoRequestService infoRequestService;

    @PostMapping("/tasks/{taskId}/info-requests")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).WA_REQUEST_INFORMATION)")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Pause my task and ask someone for a document or an answer",
            description = "The task becomes awaiting_info and can't be decided until every request is closed; its SLA "
                    + "clock pauses. A pool task is claimed for you. 422 WORKFLOW_INFO_REQUESTS_DISABLED when the "
                    + "stage doesn't allow requests.")
    public InfoRequestView raise(@PathVariable("taskId") UUID taskId, @Valid @RequestBody RaiseInfoRequest request) {
        return infoRequestService.raise(taskId, request.requestedFromUserId(), request.message(), request.dueAt());
    }

    @PostMapping("/info-requests/{id}/respond")
    @Operation(summary = "Answer a request addressed to me (note and/or supporting documents)",
            description = "Attaching documents needs docm.documents.view (decision 18).")
    public InfoRequestView respond(@PathVariable("id") UUID id, @Valid @RequestBody RespondInfoRequest request) {
        return infoRequestService.respond(id, request);
    }

    @PostMapping("/info-requests/{id}/decline")
    @Operation(summary = "Decline a request addressed to me (reason required)")
    public InfoRequestView decline(@PathVariable("id") UUID id, @Valid @RequestBody DeclineInfoRequest request) {
        return infoRequestService.decline(id, request.reason());
    }

    @PostMapping("/info-requests/{id}/withdraw")
    @Operation(summary = "Withdraw my own request")
    public InfoRequestView withdraw(@PathVariable("id") UUID id,
                                    @Valid @RequestBody(required = false) WithdrawInfoRequest request) {
        return infoRequestService.withdraw(id, request == null ? null : request.reason());
    }

    @PostMapping("/info-requests/{id}/reassign")
    @Operation(summary = "Redirect an open request to someone else (the submitter or a tenant admin)")
    public InfoRequestView reassign(@PathVariable("id") UUID id, @Valid @RequestBody ReassignInfoRequest request) {
        return infoRequestService.reassign(id, request.requestedFromUserId(), request.reason());
    }

    @GetMapping("/instances/{id}/info-requests")
    @Operation(summary = "Every information request of a workflow run, oldest first")
    public List<InfoRequestView> forInstance(@PathVariable("id") UUID id) {
        return infoRequestService.listForInstance(id);
    }

    @GetMapping("/info-requests/my")
    @Operation(summary = "Requests addressed to me (\"Requests for me\"), newest first",
            description = "status: open (default) | answered | declined | cancelled | all")
    public PageResponse<InfoRequestView> mine(@RequestParam(defaultValue = "open") String status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        InfoRequestStatus filter = "all".equalsIgnoreCase(status) ? null : InfoRequestStatus.valueOf(status.toLowerCase());
        return PageResponse.from(infoRequestService.addressedToMe(filter,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))));
    }
}
