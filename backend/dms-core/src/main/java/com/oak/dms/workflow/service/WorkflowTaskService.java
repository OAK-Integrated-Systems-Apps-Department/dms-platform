package com.oak.dms.workflow.service;

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

    /** reason is mandatory for a rejection (400 otherwise), optional for an approval. */
    TaskActionResponse act(UUID taskId, TaskDecision decision, String reason);
}
