package com.oak.dms.workflow.service;

import com.oak.dms.workflow.dto.PersonOptionResponse;
import com.oak.dms.workflow.dto.TaskActionResponse;

import java.util.List;
import java.util.UUID;

/**
 * Moving a task to another holder without creating a new one (Phase 2 S3,
 * decisions 1-4): the task stays pending/claimed, delegated_to decides who
 * may act, and delegated_from keeps the original holder.
 */
public interface WorkflowDelegationService {

    /** The holder hands their task on. Handing it back to the original holder clears the delegation (A3). */
    TaskActionResponse delegate(UUID taskId, UUID delegateUserId, String note);

    /** An admin (wa.reassign-workflow-task) moves any open task - including a parked one - to a user. */
    TaskActionResponse reassign(UUID taskId, UUID userId, String reason);

    /** Users the caller could delegate (or, as an admin, reassign) this task to. */
    List<PersonOptionResponse> delegateCandidates(UUID taskId, String search, int limit);
}
