package com.oak.dms.workflow.service;

import com.oak.dms.signature.dto.SignAndApproveRequest;
import com.oak.dms.workflow.dto.TaskActionResponse;
import com.oak.dms.workflow.enums.TaskDecision;

import java.util.UUID;

/**
 * Acting on approval tasks (W6/W10). Every action re-checks its guards
 * under the instance -> stage -> task locks, so two concurrent approvers
 * can never both conclude a stage (decision 6).
 */
public interface WorkflowTaskService {

    TaskActionResponse claim(UUID taskId);

    TaskActionResponse release(UUID taskId);

    /**
     * reason is mandatory for a rejection (400 otherwise), optional for an
     * approval. 409 WORKFLOW_SIGNATURE_REQUIRED if the stage requires a
     * signature to approve - use {@link #actWithSignature} instead.
     */
    TaskActionResponse act(UUID taskId, TaskDecision decision, String reason);

    /** 1.4 Digital Signature Integration: signs and approves in one transaction (decision 1). */
    TaskActionResponse actWithSignature(UUID taskId, SignAndApproveRequest request);
}
