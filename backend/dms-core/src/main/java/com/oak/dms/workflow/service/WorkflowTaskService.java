package com.oak.dms.workflow.service;

import com.oak.dms.signature.dto.SignAndApproveRequest;
import com.oak.dms.workflow.dto.ReviewLockResponse;
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

    /**
     * V120: step-up re-authentication to even view an already-decided task again, and acquire the soft
     * review lock on its stage. 409 WORKFLOW_RETRACTION_WINDOW_CLOSED if the stage is no longer active
     * (a later decision already closed it) - the caller may still view their own evidence elsewhere, but
     * not through this reviewable session.
     */
    ReviewLockResponse openForReview(UUID taskId, String password, String totp);

    /** Renews the review lock while the signer is still looking - a no-op 409 if they no longer hold it (expired or released). */
    ReviewLockResponse renewReviewLock(UUID taskId);

    /** Ends the review session with no change - releases the lock if the caller holds it, silently otherwise. */
    void closeReview(UUID taskId);

    /**
     * Retracts the caller's own approval - only while their stage is still active, only the signer who
     * made it. If a signature was attached, it's invalidated and the document reverts to the version
     * from before it was placed. The task goes back to pending/claimed so the same person can re-decide;
     * the original decision is never erased, only superseded (the audit log keeps the full before/after).
     */
    TaskActionResponse retract(UUID taskId, String reason, String password, String totp);
}
