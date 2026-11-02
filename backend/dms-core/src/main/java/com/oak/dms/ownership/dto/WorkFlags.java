package com.oak.dms.ownership.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * In-flight approval work of a departing user (D6): flagged, never reassigned automatically. Document id and title
 * are present only where the caller can open the document.
 */
public record WorkFlags(List<OpenTask> openTasks, List<StartedRun> startedRuns, int stuckTaskCount, int startedRunCount) {

    /** A task the user holds: pending, claimed or awaiting information, in a running approval. {@code route} is assigned, claimed, delegated or escalated. */
    public record OpenTask(UUID taskId, UUID instanceId, UUID documentId, String documentTitle, String stageName,
                           String status, String route, OffsetDateTime dueAt, String instanceUrl, String documentUrl,
                           boolean canReassign) {
    }

    /** A run the user started that is still in progress. Its initiator can't change; it can only be cancelled. */
    public record StartedRun(UUID instanceId, UUID documentId, String documentTitle, OffsetDateTime startedAt,
                             String instanceUrl, boolean canCancel) {
    }
}
